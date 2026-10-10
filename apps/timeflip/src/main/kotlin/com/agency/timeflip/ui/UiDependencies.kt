package com.agency.timeflip.ui

import android.graphics.Bitmap
import com.agency.timeflip.domain.model.PhotoId
import com.agency.timeflip.domain.port.PhotoStore
import com.agency.timeflip.domain.port.ProjectRepository
import com.agency.timeflip.domain.port.VideoExportEngine
import com.agency.timeflip.ui.ports.AlignmentRunner
import com.agency.timeflip.ui.ports.OnboardingStore
import java.io.File

/** Decodes a bounded preview of a photo copy. Implemented by the engine's PhotoBitmaps. */
fun interface BitmapLoader {
    suspend fun decode(id: PhotoId, maxEdge: Int): Bitmap?
}

/**
 * Everything the screens use, handed over by the composition root. Screens never reach into the
 * AppContainer themselves, which keeps them testable with fakes.
 *
 * [awaitStartupRecovery] must finish before the first import or export, so a damaged store is
 * reconciled first.
 */
class UiDependencies(
    val projects: ProjectRepository,
    val photos: PhotoStore,
    val bitmaps: BitmapLoader,
    val aligner: AlignmentRunner,
    val exporter: VideoExportEngine,
    val exportsDir: File,
    val onboarding: OnboardingStore,
    val awaitStartupRecovery: suspend () -> Unit,
)
