package com.agency.timeflip.engine.auto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.agency.timeflip.domain.geometry.AffineMatrix
import com.agency.timeflip.domain.geometry.PhotoPlacement
import com.agency.timeflip.domain.image.GrayRaster
import com.agency.timeflip.domain.model.AlignmentEstimate
import com.agency.timeflip.domain.model.AlignmentStatus
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.ImagePair
import com.agency.timeflip.domain.model.PixelSize
import com.agency.timeflip.domain.model.SourcePhoto
import com.agency.timeflip.domain.port.PhotoStore
import com.agency.timeflip.engine.alignment.FeatureAligner
import com.agency.timeflip.engine.alignment.WorkingRaster
import com.agency.timeflip.engine.image.PhotoBitmaps
import com.agency.timeflip.engine.render.AndroidMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Estimates an automatic alignment for an [ImagePair]. Both photographs are cover-fitted into the same
 * working frame, so the rasters share the frame coordinates and the estimate maps directly onto the
 * domain's AlignmentTransform.
 *
 * Lives outside engine/alignment because it depends on Android types. The pure solver stays in
 * engine/alignment.
 */
class AutoAligner(
    private val photoStore: PhotoStore,
    private val photoBitmaps: PhotoBitmaps,
) {

    suspend fun align(pair: ImagePair, frame: PixelSize): AlignmentEstimate {
        val working = WorkingRaster.workingFrame(frame)
        val before = workingRaster(pair.before, working) ?: return failed()
        val after = workingRaster(pair.after, working) ?: return failed()
        return withContext(Dispatchers.Default) {
            FeatureAligner().estimate(before, after, working)
        }
    }

    private suspend fun workingRaster(photo: SourcePhoto, working: PixelSize): GrayRaster? {
        if (photoStore.originalPath(photo.id) == null) return null
        // Decode at twice the working size so the downscale on the canvas stays smooth.
        val decoded = photoBitmaps.decode(photo.id, maxEdge = working.longestEdge * 2) ?: return null
        return withContext(Dispatchers.Default) { renderGray(decoded, photo.size, working) }
    }

    private fun renderGray(source: Bitmap, sourceSize: PixelSize, working: PixelSize): GrayRaster {
        val target = Bitmap.createBitmap(working.width, working.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawColor(Color.GRAY)

        // Bitmap pixels → oriented source pixels (undoing the sample reduction), then the cover fit into the working frame.
        val toSource = AffineMatrix.scale(
            sourceSize.width.toDouble() / source.width,
            sourceSize.height.toDouble() / source.height,
        )
        val placement = toSource.then(PhotoPlacement.coverBase(sourceSize, working))
        val matrix = Matrix()
        AndroidMatrix.set(matrix, FloatArray(9), placement)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, matrix, paint)

        val argb = IntArray(working.width * working.height)
        target.getPixels(argb, 0, working.width, 0, 0, working.width, working.height)
        return WorkingRaster.grayFromArgb(argb, working.width, working.height)
    }

    private fun failed(): AlignmentEstimate =
        AlignmentEstimate(AlignmentTransform.IDENTITY, confidence = 0.0, inlierCount = 0, status = AlignmentStatus.FAILED)
}
