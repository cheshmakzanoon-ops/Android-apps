package com.agency.stickerjet.ui.editor

import androidx.lifecycle.SavedStateHandle
import com.agency.stickerjet.domain.AlphaMask
import com.agency.stickerjet.domain.ArgbFrame
import com.agency.stickerjet.domain.BackgroundMode
import com.agency.stickerjet.domain.ExportPlan
import com.agency.stickerjet.domain.NormalizedPoint
import com.agency.stickerjet.domain.ProjectRepository
import com.agency.stickerjet.domain.StickerLimits
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.domain.StrokeMode
import com.agency.stickerjet.domain.SubjectSegmenter
import com.agency.stickerjet.fakes.FakeFrameSource
import com.agency.stickerjet.fakes.FakeSubjectSegmenter
import com.agency.stickerjet.fakes.InMemoryProjectRepository
import com.agency.stickerjet.ui.navigation.Routes
import com.agency.stickerjet.ui.ports.ExportFailureKind
import com.agency.stickerjet.ui.ports.ExportPort
import com.agency.stickerjet.ui.testdoubles.FailingPreview
import com.agency.stickerjet.ui.testdoubles.FixedStorage
import com.agency.stickerjet.ui.testdoubles.OpaqueMasks
import com.agency.stickerjet.ui.testdoubles.ScriptedExporter
import com.agency.stickerjet.ui.testdoubles.TestFiles
import com.agency.stickerjet.ui.testdoubles.testProject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Editor state transitions, driven through the session without a main dispatcher. Covers undo history,
 * skipped no-op commits, input validation, subject detection, export rules and persistence failures.
 */
class EditorViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repository: InMemoryProjectRepository
    private lateinit var files: TestFiles
    private val storage = FixedStorage(hasRoom = true)

    @Before
    fun setUp() {
        repository = InMemoryProjectRepository(listOf(testProject(ID)))
        files = TestFiles(tmp.root)
        files.sourceFile(ID).apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(16))
        }
    }

    @Test
    fun load_reads_the_project_and_builds_the_trim_loop_for_its_window() {
        val vm = viewModel()
        runBlocking { vm.session.load() }
        val state = vm.state.value
        assertFalse(state.loading)
        assertFalse(state.sourceMissing)
        val project = requireNotNull(state.project)
        val loop = state.trimLoop as LoopState.Ready<ArgbFrame>
        assertEquals(ExportPlan.frameCount(project.trim), loop.frames.size)
        assertFalse(state.canUndo)
    }

    @Test
    fun a_missing_project_reports_not_found() {
        val vm = viewModel(id = "missing")
        runBlocking { vm.session.load() }
        assertTrue(vm.state.value.notFound)
        assertEquals(null, vm.state.value.project)
    }

    @Test
    fun a_drag_stays_a_draft_until_it_is_committed() {
        val vm = loaded()
        vm.session.draftTrimStart(1_000)
        assertEquals(1_000L, vm.state.value.project!!.trim.startMs)
        assertFalse(vm.state.value.canUndo)
        assertEquals(0L, storedProject().trim.startMs)

        runBlocking { vm.session.commitDraft() }
        assertTrue(vm.state.value.canUndo)
        assertEquals(1_000L, storedProject().trim.startMs)
    }

    @Test
    fun undo_and_redo_step_through_committed_trims() {
        val vm = loaded()
        runBlocking {
            vm.session.draftTrimStart(1_000)
            vm.session.commitDraft()
            vm.session.draftTrimStart(1_500)
            vm.session.commitDraft()
            vm.session.step { it.undo() }
        }
        assertEquals(1_000L, vm.state.value.project!!.trim.startMs)
        runBlocking { vm.session.step { it.redo() } }
        assertEquals(1_500L, vm.state.value.project!!.trim.startMs)
    }

    @Test
    fun a_commit_that_changes_nothing_adds_no_undo_step() {
        val vm = loaded()
        runBlocking {
            vm.session.commitDraft()
            vm.session.commitDraft()
        }
        assertFalse(vm.state.value.canUndo)
    }

    @Test
    fun switching_the_background_is_one_undoable_step() {
        val vm = loaded()
        runBlocking { vm.session.setBackgroundMode(BackgroundMode.REMOVE_BACKGROUND) }
        assertEquals(BackgroundMode.REMOVE_BACKGROUND, vm.state.value.project!!.background)
        assertTrue(vm.state.value.canUndo)
    }

    @Test
    fun caption_text_is_normalized_on_commit() {
        val vm = loaded()
        runBlocking { vm.session.commitCaptionText("   Hello     there   ") }
        assertEquals("Hello there", vm.state.value.project!!.caption.text)
    }

    @Test
    fun caption_text_is_capped_at_the_domain_limit() {
        val vm = loaded()
        runBlocking { vm.session.commitCaptionText("x".repeat(100)) }
        assertEquals(StickerLimits.MAX_CAPTION_CHARS, vm.state.value.project!!.caption.text.length)
    }

    @Test
    fun placement_drag_is_clamped_to_the_placement_limits() {
        val vm = loaded()
        vm.session.draftPlacement(scale = 10f, offsetX = 5f, offsetY = -5f)
        val transform = vm.state.value.project!!.transform
        assertEquals(PlacementLimits.MAX_SCALE, transform.scale, 0f)
        assertEquals(PlacementLimits.MAX_OFFSET, transform.offsetX, 0f)
        assertEquals(-PlacementLimits.MAX_OFFSET, transform.offsetY, 0f)
    }

    @Test
    fun placement_ignores_non_finite_input() {
        val vm = loaded()
        vm.session.draftPlacement(Float.NaN, 0f, 0f)
        assertEquals(1f, vm.state.value.project!!.transform.scale, 0f)
    }

    @Test
    fun four_quarter_turns_return_to_the_start_and_each_turn_is_undoable() {
        val vm = loaded()
        runBlocking { repeat(4) { vm.session.rotateQuarterTurn() } }
        assertEquals(0, vm.state.value.project!!.transform.quarterTurns)
        runBlocking { vm.session.step { it.undo() } }
        assertEquals(3, vm.state.value.project!!.transform.quarterTurns)
    }

    @Test
    fun no_person_found_keeps_the_original_background_and_still_has_a_matte() {
        val vm = loaded()
        runBlocking { vm.session.enterTab(EditorTab.SUBJECT) }
        assertEquals(SubjectDetection.NO_PERSON, vm.state.value.detection)
        val matte = requireNotNull(vm.state.value.matte)
        assertTrue(matte.pixels.all { (it ushr 24) == 0xFF })
    }

    @Test
    fun a_found_person_makes_the_outside_of_the_subject_transparent() {
        // The still frame is 384x288 (480x360 scaled to the 384 px side limit), so the mask must match.
        val mask = AlphaMask(STILL_WIDTH, STILL_HEIGHT, ByteArray(STILL_WIDTH * STILL_HEIGHT))
        val vm = viewModel(segmenter = FakeSubjectSegmenter.fixed(mask))
        runBlocking { vm.session.enterTab(EditorTab.SUBJECT) }
        assertEquals(SubjectDetection.FOUND, vm.state.value.detection)
        assertEquals(0, vm.state.value.matte!!.pixels[0] ushr 24)
    }

    @Test
    fun brush_points_are_clamped_and_reset_removes_every_stroke() {
        val vm = loaded()
        runBlocking {
            vm.session.addStrokeNow(
                StrokeMode.ERASE,
                listOf(NormalizedPoint(0.5f, 0.5f), NormalizedPoint(1.4f, -0.2f)),
                radius = 0.05f,
            )
        }
        val stroke = vm.state.value.project!!.maskStrokes.single()
        assertEquals(1f, stroke.points[1].x, 0f)
        assertEquals(0f, stroke.points[1].y, 0f)

        runBlocking { vm.session.resetMaskNow() }
        assertTrue(vm.state.value.project!!.maskStrokes.isEmpty())
    }

    @Test
    fun empty_strokes_and_zero_radius_are_dropped_without_an_undo_step() {
        val vm = loaded()
        runBlocking {
            vm.session.addStrokeNow(StrokeMode.RESTORE, emptyList(), radius = 0.05f)
            vm.session.addStrokeNow(StrokeMode.RESTORE, listOf(NormalizedPoint(0.5f, 0.5f)), radius = 0f)
        }
        assertTrue(vm.state.value.project!!.maskStrokes.isEmpty())
        assertFalse(vm.state.value.canUndo)
    }

    @Test
    fun a_successful_export_reloads_the_draft_with_the_new_record_and_starts_a_fresh_history() {
        val exporter = ScriptedExporter.success(repository, File(tmp.root, "out.webp"))
        val vm = viewModel(exporter = exporter)
        runBlocking {
            vm.session.load()
            vm.session.draftTrimStart(1_000)
            vm.session.commitDraft()
            vm.session.exportSticker()
        }
        val state = vm.state.value
        val done = state.export as ExportState.Done
        assertEquals("out.webp", done.file.name)
        assertEquals(1, state.project!!.exports.size)
        assertFalse(state.canUndo)
        assertEquals(1, exporter.calls)
    }

    @Test
    fun a_failed_export_keeps_the_draft_and_reports_the_reason() {
        val exporter = ScriptedExporter.failure(repository, ExportFailureKind.TOO_LARGE)
        val vm = viewModel(exporter = exporter)
        runBlocking {
            vm.session.load()
            vm.session.draftTrimStart(1_000)
            vm.session.commitDraft()
            vm.session.exportSticker()
        }
        assertEquals(ExportState.Failed(ExportFailureKind.TOO_LARGE), vm.state.value.export)
        assertEquals(1_000L, vm.state.value.project!!.trim.startMs)
        assertEquals(1_000L, storedProject().trim.startMs)
    }

    @Test
    fun a_missing_source_blocks_export_before_the_exporter_runs() {
        files.sourceFile(ID).delete()
        val exporter = ScriptedExporter.failure(repository, ExportFailureKind.INTERNAL)
        val vm = viewModel(exporter = exporter)
        runBlocking {
            vm.session.load()
            vm.session.exportSticker()
        }
        assertTrue(vm.state.value.sourceMissing)
        assertEquals(ExportState.Failed(ExportFailureKind.SOURCE_MISSING), vm.state.value.export)
        assertEquals(0, exporter.calls)
    }

    @Test
    fun low_storage_blocks_export_before_the_exporter_runs() {
        storage.hasRoom = false
        val exporter = ScriptedExporter.failure(repository, ExportFailureKind.INTERNAL)
        val vm = viewModel(exporter = exporter)
        runBlocking {
            vm.session.load()
            vm.session.exportSticker()
        }
        assertEquals(ExportState.Failed(ExportFailureKind.LOW_STORAGE), vm.state.value.export)
        assertEquals(0, exporter.calls)
        storage.hasRoom = true
    }

    @Test
    fun a_save_failure_is_reported_while_the_draft_stays_in_memory() {
        val vm = viewModel(projects = SaveFailingRepository(repository))
        runBlocking {
            vm.session.load()
            vm.session.draftTrimStart(1_000)
            vm.session.commitDraft()
        }
        assertTrue(vm.state.value.saveFailed)
        assertEquals(1_000L, vm.state.value.project!!.trim.startMs)
    }

    private fun storedProject(): StickerProject = runBlocking { repository.load(ID)!! }

    private fun loaded(): EditorViewModel {
        val vm = viewModel()
        runBlocking { vm.session.load() }
        return vm
    }

    private fun viewModel(
        id: String = ID,
        projects: ProjectRepository = repository,
        segmenter: SubjectSegmenter = FakeSubjectSegmenter.noSubject(),
        exporter: ExportPort = ScriptedExporter.success(repository, File(tmp.root, "out.webp")),
    ): EditorViewModel = EditorViewModel(
        savedState = SavedStateHandle(mapOf(Routes.PROJECT_ID_ARG to id)),
        projects = projects,
        files = files,
        frames = FakeFrameSource(),
        segmenter = segmenter,
        masks = OpaqueMasks,
        exporter = exporter,
        preview = FailingPreview(),
        storage = storage,
        clock = { NOW },
    )

    /** Delegates reads and fails every write, to test the persistence error path. */
    private class SaveFailingRepository(private val inner: InMemoryProjectRepository) : ProjectRepository {
        override suspend fun list(): List<StickerProject> = inner.list()
        override suspend fun load(id: String): StickerProject? = inner.load(id)
        override suspend fun save(project: StickerProject) {
            throw IllegalStateException("disk full")
        }
        override suspend fun delete(id: String) = inner.delete(id)
    }

    private companion object {
        const val ID = "p1"
        const val NOW = 5_000L
        const val STILL_WIDTH = 384
        const val STILL_HEIGHT = 288
    }
}
