package com.agency.stickerjet.ui.common

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.agency.stickerjet.domain.ArgbFrame

/** Converts a decoded frame (packed 0xAARRGGBB) into a Compose bitmap for display. */
fun ArgbFrame.toImageBitmap(): ImageBitmap = pixelsToImageBitmap(pixels, width, height)

/** Builds an ARGB_8888 bitmap from packed pixels. Pixel order is row-major, as in [ArgbFrame]. */
fun pixelsToImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
