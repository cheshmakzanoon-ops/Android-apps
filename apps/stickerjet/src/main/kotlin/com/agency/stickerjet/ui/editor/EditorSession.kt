package com.agency.stickerjet.ui.editor

import com.agency.stickerjet.domain.AlphaMask
import com.agency.stickerjet.domain.BackgroundMode
import com.agency.stickerjet.domain.EditHistory
import com.agency.stickerjet.domain.FrameSource
import com.agency.stickerjet.domain.ProjectRepository
import com.agency.stickerjet.domain.StickerLimits
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.domain.SubjectSegmenter
import com.agency.stickerjet.domain.TrimPolicy
import com.agency.stickerjet.domain.withBackground
import com.agency.stickerjet.domain.withCaption
import com.agency.stickerjet.domain.withTransform
import com.agency.stickerjet.domain.withTrim
import com.agency.stickerjet.ui.ports.ExportPort
import com.agency.stickerjet.ui.ports.MaskRefinementPort
import com.agency.stickerjet.ui.ports.PreviewPort
import com.agency.stickerjet.ui.ports.ProjectFilesPort
import com.agency.stickerjet.ui.ports.StoragePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * The editor's state machine, without any Android lifecycle. [EditorViewModel] owns one instance and
 * forwards user actions to it; JVM tests drive it directly. All functions run on the caller's
 * dispatcher, which is Main in the app, so [history] is never touched from two threads.
 *
 * Drag helpers update the draft in [state] only. [commit] records one undo step and saves. A commit
 * that changes nothing is skipped, so undo never fills with no-op steps.
 */
internal class EditorSession(
    val projectId: String,
    val projects: ProjectRepository,
    val files: ProjectFilesPort,
    val frames: FrameSource,
    val segmenter: SubjectSegmenter,
    val masks: MaskRefinementPort,
    val exporter: ExportPort,
    val preview: PreviewPort,
    val storage: StoragePort,
    private val clock: () -> Long,
) {
    val state = MutableStateFlow(EditorUiState())

    /** Undo history of committed projects. Null until the project has loaded. */
    var history: EditHistory<StickerProject>? = null

    /** Base mask from the last successful detection on the still frame. Null when none applies. */
    var baseMask: AlphaMask? = null

    /** True when the preview loop no longer matches the project and must be rendered again. */
    var previewStale: Boolean = true

    val project: StickerProject? get() = state.value.project

    val now: Long get() = clock()

    /** Loads the project and the trim loop. A missing project sets [EditorUiState.notFound]. */
    suspend fun load() {
        state.update { it.copy(loading = true, loadFailed = false, notFound = false) }
        val stored = try {
            projects.load(projectId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(loading = false, loadFailed = true) }
            return
        }
        if (stored == null) {
            state.update { it.copy(loading = false, notFound = true) }
            return
        }
        val loaded = if (TrimPolicy.isValid(stored.trim, stored.source.durationMs)) {
            stored
        } else {
            stored.withTrim(stored.trim, now)
        }
        history = EditHistory(loaded)
        val missing = withContext(Dispatchers.IO) { !files.sourceFile(projectId).isFile }
        state.update {
            it.copy(
                loading = false,
                project = loaded,
                canUndo = false,
                canRedo = false,
                sourceMissing = missing,
            )
        }
        if (!missing) refreshTrimLoop()
    }

    /** Prepares what a tab needs when it is opened. Each tab is cheap to open a second time. */
    suspend fun enterTab(tab: EditorTab) {
        when (tab) {
            EditorTab.TRIM -> {
                if (state.value.trimLoop !is LoopState.Ready) refreshTrimLoop()
            }
            EditorTab.SUBJECT -> {
                if (state.value.detection == SubjectDetection.IDLE) detectSubjectNow()
            }
            EditorTab.STYLE -> {
                ensureStill()
            }
            EditorTab.PREVIEW -> {
                ensurePreview()
            }
        }
    }

    /** Moves the start handle in the draft. */
    fun draftTrimStart(requestedMs: Long) {
        val current = project ?: return
        val sourceMs = current.source.durationMs
        if (sourceMs < StickerLimits.MIN_STICKER_MS) return
        val trim = TrimEditor.moveStart(current.trim, requestedMs, sourceMs)
        state.update { it.copy(project = current.withTrim(trim, now)) }
    }

    /** Moves the end handle in the draft. */
    fun draftTrimEnd(requestedMs: Long) {
        val current = project ?: return
        val sourceMs = current.source.durationMs
        if (sourceMs < StickerLimits.MIN_STICKER_MS) return
        val trim = TrimEditor.moveEnd(current.trim, requestedMs, sourceMs)
        state.update { it.copy(project = current.withTrim(trim, now)) }
    }

    /** Sets scale and offsets in the draft, clamped to the placement limits. */
    fun draftPlacement(scale: Float, offsetX: Float, offsetY: Float) {
        val current = project ?: return
        if (!scale.isFinite() || !offsetX.isFinite() || !offsetY.isFinite()) return
        val transform = current.transform.copy(
            scale = scale.coerceIn(PlacementLimits.MIN_SCALE, PlacementLimits.MAX_SCALE),
            offsetX = offsetX.coerceIn(-PlacementLimits.MAX_OFFSET, PlacementLimits.MAX_OFFSET),
            offsetY = offsetY.coerceIn(-PlacementLimits.MAX_OFFSET, PlacementLimits.MAX_OFFSET),
        )
        state.update { it.copy(project = current.withTransform(transform, now)) }
    }

    /** Records the draft as one undo step when it differs from the last committed state. */
    suspend fun commitDraft() {
        val draft = project ?: return
        commit(draft)
    }

    /** Applies [edit] to the draft and commits the result. */
    suspend fun commitIfPresent(edit: (StickerProject) -> StickerProject) {
        val current = project ?: return
        commit(edit(current))
    }

    suspend fun setBackgroundMode(mode: BackgroundMode) {
        commitIfPresent { p -> p.withBackground(mode, now) }
    }

    /** Commits the caption text. The domain normalizes it, so spaces typed at the edges are removed here. */
    suspend fun commitCaptionText(text: String) {
        commitIfPresent { p -> p.withCaption(p.caption.copy(text = text), now) }
    }

    /** Commits the draft as the new state, saves it and refreshes whatever depends on it. */
    suspend fun commit(next: StickerProject) {
        val h = history ?: return
        val before = h.current
        if (next == before) {
            state.update { it.copy(project = next) }
            return
        }
        h.commit(next)
        previewStale = true
        state.update { it.copy(project = next, canUndo = h.canUndo, canRedo = h.canRedo) }
        persist(next)
        applyEffects(before, next)
    }

    /** Undo or redo: [move] returns the restored project, or null when there is nothing to step to. */
    suspend fun step(move: (EditHistory<StickerProject>) -> StickerProject?) {
        val h = history ?: return
        val before = h.current
        val next = move(h) ?: return
        previewStale = true
        state.update { it.copy(project = next, canUndo = h.canUndo, canRedo = h.canRedo) }
        persist(next)
        applyEffects(before, next)
    }

    /** Work that follows a change: a new trim invalidates the still frame, new strokes rebuild the matte. */
    suspend fun applyEffects(before: StickerProject, after: StickerProject) {
        if (after.trim != before.trim) {
            baseMask = null
            state.update {
                it.copy(still = null, stillFailed = false, matte = null, detection = SubjectDetection.IDLE)
            }
            refreshTrimLoop()
        } else if (after.maskStrokes != before.maskStrokes) {
            recomputeMatte()
        }
    }

    /** Saves [saved]. A failure is shown but never discards the in-memory draft. */
    suspend fun persist(saved: StickerProject) {
        val ok = try {
            projects.save(saved)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        state.update { it.copy(saveFailed = !ok) }
    }
}
