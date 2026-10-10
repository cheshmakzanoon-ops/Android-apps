package com.agency.timeflip.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.agency.timeflip.domain.model.PhotoId
import com.agency.timeflip.ui.BitmapLoader
import com.agency.timeflip.ui.theme.TimeFlipColor
import com.agency.timeflip.ui.theme.TimeFlipRadius

/**
 * A decoded, downscaled preview of a photo copy. The decode is bounded by [maxEdge], so memory stays
 * small. While it loads, or when the copy is missing, a neutral placeholder is shown.
 */
@Composable
fun PhotoThumbnail(
    photoId: PhotoId?,
    loader: BitmapLoader,
    maxEdge: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, photoId, loader, maxEdge) {
        value = if (photoId == null) null else loader.decode(photoId, maxEdge)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(TimeFlipRadius.Small))
            .background(TimeFlipColor.SurfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Image,
                contentDescription = contentDescription,
                tint = TimeFlipColor.OnSurfaceVariant,
            )
        }
    }
}
