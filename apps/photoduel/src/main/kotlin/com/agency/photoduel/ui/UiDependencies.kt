package com.agency.photoduel.ui

import android.net.Uri
import com.agency.photoduel.domain.model.PreparedPhoto
import com.agency.photoduel.domain.port.PhotoDuelService
import com.agency.photoduel.domain.port.ServerClock
import kotlinx.coroutines.flow.Flow

// Frozen seam between the UI (PD-D1) and the composition root, platform and engine (PD-D5, PD-D4).
// The UI never touches Context, Intents, ContentResolver or Bitmap directly.

/** Everything the UI needs. Built once in app/AppContainer.kt. */
data class UiDependencies(
    val service: PhotoDuelService,
    val clock: ServerClock,
    val photoPicking: PhotoPicking,
    val photoProcessing: PhotoProcessing,
    val invitationSharing: InvitationSharing,
    /** Invitation tokens received through photoduel://invite/{token} while the app is open or launching. */
    val pendingInviteTokens: Flow<String>,
    /** False when no verified API base URL is configured for this build. The UI then shows a setup state. */
    val serverConfigured: Boolean,
)

/** Launches the system Photo Picker (or the camera chooser). Implemented in platform/. */
fun interface PhotoPicking {
    fun pick(onResult: (Uri?) -> Unit)
}

/** Decodes, rotates, resizes, strips metadata and encodes a chosen photo. Implemented in engine/android/. */
interface PhotoProcessing {
    /** Runs on a background dispatcher. Honors coroutine cancellation between stages. */
    suspend fun prepare(source: Uri, onStage: (ProcessingStage) -> Unit): PreparedPhoto
}

enum class ProcessingStage { READING, DECODING, ENCODING, DONE }

/** Opens the system share sheet with a plain-text invitation. Implemented in platform/. */
fun interface InvitationSharing {
    fun share(roomName: String, inviteUrl: String)
}
