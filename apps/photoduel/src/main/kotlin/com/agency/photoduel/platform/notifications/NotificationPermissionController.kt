package com.agency.photoduel.platform.notifications

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.agency.photoduel.platform.ActivityHost

/**
 * Opt-in notification permission (POST_NOTIFICATIONS, Android 13 and later).
 *
 * LIMITATION, stated plainly: this build does not schedule or post any reminder or notification. The
 * server never pushes (decision: no server-sent notifications), and there is no local scheduler.
 * [remindersImplemented] is therefore false and the UI must not describe reminders as working. This
 * controller exists so that, when reminders are built, the permission flow is already correct:
 *
 *  - It never runs at launch. [request] must be called from a user action, such as switching on
 *    reminders in settings.
 *  - [status] reads state without prompting.
 *  - After the user has refused twice, Android no longer shows the dialog; [status] then reports
 *    [Status.USE_SETTINGS] and [openSystemSettings] is the only way forward.
 */
class NotificationPermissionController(
    private val appContext: Context,
    private val host: ActivityHost,
) {
    enum class Status {
        /** Notifications can be shown. */
        GRANTED,

        /** Android 13+, not granted, and the system dialog can still be shown. */
        CAN_ASK,

        /** Refused for good (or switched off below Android 13). Only system settings can change it. */
        USE_SETTINGS,
    }

    /** False in this build. Do not present reminders as available. */
    val remindersImplemented: Boolean = false

    private var launcher: ActivityResultLauncher<String>? = null
    private var pending: ((Status) -> Unit)? = null

    /** Call before setContent, on every activity creation. */
    fun attach(activity: ComponentActivity) {
        launcher = activity.activityResultRegistry.register(
            KEY,
            activity,
            ActivityResultContracts.RequestPermission(),
        ) { granted -> onResult(granted) }
    }

    fun detach(activity: ComponentActivity) {
        launcher = null
        if (activity.isFinishing) pending = null
    }

    fun status(): Status {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Below Android 13 there is no runtime permission; the user can only mute the app in settings.
            return if (NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
                Status.GRANTED
            } else {
                Status.USE_SETTINGS
            }
        }
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) Status.GRANTED else Status.CAN_ASK
    }

    /**
     * Shows the system permission dialog if it can be shown, then reports the resulting [Status].
     * Call only in response to the user switching reminders on.
     */
    fun request(onResult: (Status) -> Unit) {
        val current = status()
        val activeLauncher = launcher
        if (current != Status.CAN_ASK || activeLauncher == null || host.current() == null) {
            onResult(current)
            return
        }
        pending = onResult
        activeLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Opens this app's notification settings. Returns false if the screen cannot be opened. */
    fun openSystemSettings(): Boolean {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
        val activity = host.current()
        return try {
            if (activity != null) {
                activity.startActivity(intent)
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            }
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun onResult(granted: Boolean) {
        val callback = pending
        pending = null
        val activity = host.current()
        val status = when {
            granted -> Status.GRANTED
            // After a refusal, "no rationale" means the system will not ask again.
            activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS) ->
                Status.USE_SETTINGS
            else -> Status.CAN_ASK
        }
        callback?.invoke(status)
    }

    private companion object {
        const val KEY = "photoduel.post-notifications"
    }
}
