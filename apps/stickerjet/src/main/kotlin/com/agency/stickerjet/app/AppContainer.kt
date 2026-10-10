package com.agency.stickerjet.app

import android.content.Context
import com.agency.stickerjet.data.FileProjectRepository
import com.agency.stickerjet.data.ImportService
import com.agency.stickerjet.data.LibraryCleaner
import com.agency.stickerjet.data.OnboardingStore
import com.agency.stickerjet.data.ProjectEnvelope
import com.agency.stickerjet.data.ProjectFiles
import com.agency.stickerjet.data.StorageGuard
import com.agency.stickerjet.engine.FrameEncoder
import com.agency.stickerjet.engine.MediaFrameSource
import com.agency.stickerjet.engine.MlKitSubjectSegmenter
import com.agency.stickerjet.engine.StickerExporter
import com.agency.stickerjet.engine.StickerPreviewRenderer
import com.agency.stickerjet.engine.MaskRefiner
import com.agency.stickerjet.domain.ProjectRepository
import com.agency.stickerjet.domain.SubjectSegmenter
import com.agency.stickerjet.domain.FrameSource
import com.agency.stickerjet.ui.ports.MaskRefinementPort
import com.agency.stickerjet.ui.ports.UiPorts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * The composition root. Builds every adapter once and hands the UI a [UiPorts] bundle. Nothing in
 * `ui` names a data or engine class; the adapters in UiAdapters.kt translate between the layers.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val clock: () -> Long = { System.currentTimeMillis() }
    private val json = ProjectEnvelope.createJson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val files = ProjectFiles(
        root = File(appContext.filesDir, "projects"),
        tempRoot = File(appContext.cacheDir, ProjectFiles.DEFAULT_TEMP_FOLDER),
    )

    private val repository: ProjectRepository = FileProjectRepository(files, json, clock)
    private val importService = ImportService(appContext.contentResolver, files, clock)
    private val storage = StorageGuard(files.root)
    private val cleaner = LibraryCleaner(files, repository)
    private val onboarding = OnboardingStore(appContext)

    private val frames: FrameSource = MediaFrameSource { projectId -> files.sourceFile(projectId) }
    private val segmenter: SubjectSegmenter = MlKitSubjectSegmenter()
    private val encoder = FrameEncoder()

    private val exporter = StickerExporter(
        frames = frames,
        segmenter = segmenter,
        encoder = encoder,
        repository = repository,
        exportsDirFor = { projectId -> files.exportsDir(projectId) },
        tempDir = files.tempDir(),
        clock = clock,
    )

    private val previewRenderer = StickerPreviewRenderer(frames, segmenter, encoder)

    /** Starts cleanup that must not block the first frame: temporary files, then interrupted imports. */
    fun runStartupMaintenance() {
        scope.launch {
            cleaner.clearTemporaryFiles()
            cleaner.removeOrphans()
        }
    }

    /** The bundle the UI consumes. Built from the same singletons, so all screens share one repository. */
    fun uiPorts(): UiPorts = UiPorts(
        projects = repository,
        frames = frames,
        segmenter = segmenter,
        files = ProjectFilesAdapter(files),
        importer = ClipImportAdapter(importService),
        exporter = ExportAdapter(exporter),
        preview = PreviewAdapter(previewRenderer),
        maintenance = MaintenanceAdapter(cleaner),
        storage = StorageAdapter(storage),
        onboarding = OnboardingAdapter(onboarding),
        masks = MaskRefinementPort { base, width, height, strokes ->
            MaskRefiner.refine(base, width, height, strokes)
        },
    )
}
