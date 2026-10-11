package com.agency.photoduel.platform.picking

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.agency.photoduel.platform.ActivityHost
import com.agency.photoduel.platform.PlatformTexts
import com.agency.photoduel.ui.PhotoPicking

/**
 * Photo selection for the whole app.
 *
 *  - Library: the system Photo Picker (`PickVisualMedia`, images only). It needs no storage permission,
 *    and no READ_MEDIA_* or ACCESS_MEDIA_LOCATION permission is declared. Devices without the picker fall
 *    back to the document picker inside the contract.
 *  - Camera (optional): when a camera app exists, a small chooser offers "Take a photo". The system
 *    camera app does the capture (`TakePicture`) into a FileProvider file in cacheDir/camera. This app
 *    never opens the camera itself, so it declares and requests no CAMERA permission at all.
 *
 * No location is requested anywhere. The launchers are registered by [attach] from
 * MainActivity.onCreate, before setContent, because Android requires registration before STARTED.
 *
 * A pick in flight when the process dies cannot be completed: its callback no longer exists, so the
 * result is dropped and the user starts again.
 */
class AndroidPhotoPicking(
    private val host: ActivityHost,
    private val cameraCache: CameraCache,
) : PhotoPicking {

    private var pickLauncher: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var cameraLauncher: ActivityResultLauncher<Uri>? = null
    private var pending: ((Uri?) -> Unit)? = null
    private var pendingCapture: CameraCache.Target? = null
    private var chooser: AlertDialog? = null

    /** Registers the launchers with [activity]. Call before setContent, on every creation. */
    fun attach(activity: ComponentActivity) {
        val registry = activity.activityResultRegistry
        pickLauncher = registry.register(
            KEY_PICK,
            activity,
            ActivityResultContracts.PickVisualMedia(),
        ) { uri -> finish(uri) }
        cameraLauncher = registry.register(
            KEY_CAMERA,
            activity,
            ActivityResultContracts.TakePicture(),
        ) { saved -> onCaptured(saved) }
    }

    /** Call from onDestroy. A request survives a configuration change but not a finished activity. */
    fun detach(activity: ComponentActivity) {
        val openChooser = chooser
        if (openChooser != null) {
            chooser = null
            openChooser.dismiss()
            finish(null)
        }
        pickLauncher = null
        cameraLauncher = null
        if (activity.isFinishing) {
            pending = null
            pendingCapture?.let(cameraCache::discard)
            pendingCapture = null
        }
    }

    override fun pick(onResult: (Uri?) -> Unit) {
        val activity = host.current()
        if (activity == null || pickLauncher == null) {
            onResult(null)
            return
        }
        abandonPending()
        pending = onResult
        if (cameraLauncher != null && cameraCache.isCameraAppAvailable(activity.packageManager)) {
            showChooser(activity)
        } else {
            launchLibrary()
        }
    }

    private fun showChooser(activity: ComponentActivity) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(PlatformTexts.PICKER_TITLE)
            .setItems(
                arrayOf<CharSequence>(PlatformTexts.PICKER_FROM_LIBRARY, PlatformTexts.PICKER_FROM_CAMERA),
            ) { _, which ->
                chooser = null
                if (which == 0) launchLibrary() else launchCamera()
            }
            .setOnCancelListener {
                chooser = null
                finish(null)
            }
            .create()
        chooser = dialog
        dialog.show()
    }

    private fun launchLibrary() {
        val launcher = pickLauncher
        if (launcher == null) {
            finish(null)
            return
        }
        try {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: ActivityNotFoundException) {
            finish(null)
        }
    }

    private fun launchCamera() {
        val launcher = cameraLauncher
        val target = cameraCache.createTarget()
        if (launcher == null || target == null) {
            finish(null)
            return
        }
        pendingCapture = target
        try {
            launcher.launch(target.uri)
        } catch (e: ActivityNotFoundException) {
            dropCapture()
            finish(null)
        } catch (e: SecurityException) {
            dropCapture()
            finish(null)
        }
    }

    private fun onCaptured(saved: Boolean) {
        val target = pendingCapture
        pendingCapture = null
        // No target means the process was recreated while the camera app was open. The request that
        // started the capture is gone, so there is nobody to hand the photo to.
        if (target == null) return
        if (saved && target.file.length() > 0L) {
            finish(target.uri)
        } else {
            cameraCache.discard(target)
            finish(null)
        }
    }

    private fun dropCapture() {
        pendingCapture?.let(cameraCache::discard)
        pendingCapture = null
    }

    /** A new request replaces an unfinished one; the older caller is told it was cancelled. */
    private fun abandonPending() {
        val old = pending
        pending = null
        chooser?.let {
            chooser = null
            it.dismiss()
        }
        dropCapture()
        old?.invoke(null)
    }

    private fun finish(uri: Uri?) {
        val callback = pending
        pending = null
        callback?.invoke(uri)
    }

    private companion object {
        const val KEY_PICK = "photoduel.pick-visual-media"
        const val KEY_CAMERA = "photoduel.take-picture"
    }
}
