package com.agency.photoduel.platform.picking

import android.net.Uri
import com.agency.photoduel.domain.model.PreparedPhoto
import com.agency.photoduel.ui.PhotoProcessing
import com.agency.photoduel.ui.ProcessingStage

/**
 * Decorates the engine pipeline so that a camera photo is deleted from cacheDir/camera as soon as
 * processing ends, whether it succeeded, failed or was cancelled. Photos that came from the system
 * Photo Picker are not ours and are never touched. A camera photo can therefore be processed once; a
 * retry after a failure needs a new capture.
 */
class CameraCleanupPhotoProcessing(
    private val delegate: PhotoProcessing,
    private val cameraCache: CameraCache,
) : PhotoProcessing {
    override suspend fun prepare(source: Uri, onStage: (ProcessingStage) -> Unit): PreparedPhoto {
        try {
            return delegate.prepare(source, onStage)
        } finally {
            cameraCache.deleteIfOwned(source)
        }
    }
}
