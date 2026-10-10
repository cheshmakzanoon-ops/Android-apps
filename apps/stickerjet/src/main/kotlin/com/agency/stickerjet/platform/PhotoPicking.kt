package com.agency.stickerjet.platform

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * Returns a function that opens the system video picker and reports the result to [onPicked].
 *
 * - Uses the Photo Picker (`PickVisualMedia`) with `VideoOnly` when the device provides one.
 * - Otherwise falls back to `GetContent("video/*")`, which opens the system document picker.
 * - Requests no runtime permission. Both paths hand back a per-file read grant only.
 * - `onPicked(null)` means the user cancelled. It is not an error.
 *
 * The returned URI is a TEMPORARY grant. It is valid only while the grant lasts, which the system
 * does not promise across process death. The caller must copy the bytes at once, through
 * `ImportService`, and must not store the URI for later. [PendingImportHolder] keeps the project id
 * and the URI string across process death, but a restored URI can already be revoked. Treat a
 * failed read as ACCESS_REVOKED and ask the user to pick again.
 *
 * The callback is read through rememberUpdatedState, so a recomposed lambda is always the one called.
 */
@Composable
fun rememberVideoPicker(onPicked: (Uri?) -> Unit): () -> Unit {
    val currentOnPicked by rememberUpdatedState(onPicked)

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> currentOnPicked(uri) }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> currentOnPicked(uri) }

    // Checked once per composition. The availability does not change while the screen is visible.
    val photoPickerAvailable = remember { ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable() }

    return remember(photoPickerAvailable, photoPicker, documentPicker) {
        val launch: () -> Unit = {
            if (photoPickerAvailable) {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            } else {
                documentPicker.launch("video/*")
            }
        }
        launch
    }
}
