package com.agency.timeflip.ui.screens.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.PixelSize
import com.agency.timeflip.domain.model.TransitionDefinition
import com.agency.timeflip.engine.render.FrameRenderer

/**
 * Live preview of one frame. It calls [FrameRenderer.draw], the same drawing path the MP4 exporter
 * uses, so the preview cannot drift from the output. The frame is scaled to fill this canvas.
 * Until both bitmaps are loaded, the canvas stays empty.
 */
@Composable
fun TransitionPreview(
    before: Bitmap?,
    after: Bitmap?,
    frame: PixelSize,
    alignment: AlignmentTransform,
    transition: TransitionDefinition,
    linearProgress: Double,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val photoBefore = before ?: return@Canvas
        val photoAfter = after ?: return@Canvas
        val scale = (size.width / frame.width).toFloat()
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.scale(scale, scale)
            FrameRenderer.draw(
                canvas = native,
                frame = frame,
                before = photoBefore,
                after = photoAfter,
                alignment = alignment,
                transition = transition,
                linearProgress = linearProgress,
            )
            native.restore()
        }
    }
}
