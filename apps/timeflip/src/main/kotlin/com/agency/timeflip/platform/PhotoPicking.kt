package com.agency.timeflip.platform

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.agency.timeflip.domain.model.PhotoSourceRef

/**
 * Remembers a launcher for the system Photo Picker, limited to images.
 *
 * - No storage or media permission is requested. The picker grants temporary read access to the
 *   chosen item only.
 * - The grant is used only during import: the data layer copies the picture into app-private storage
 *   right away. The picker URI is never saved in a project, so a revoked or expired grant cannot
 *   break a saved transformation.
 * - A null result (the user dismissed the picker) does nothing.
 *
 * The returned lambda starts the picker. [onPicked] always receives the latest callback instance.
 */
@Composable
fun rememberSystemPhotoPicker(onPicked: (PhotoSourceRef) -> Unit): () -> Unit {
    val latestOnPicked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) latestOnPicked(PhotoSourceRef(uri.toString()))
    }
    return remember(launcher) {
        {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
}
