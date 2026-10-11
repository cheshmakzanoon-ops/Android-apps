package com.agency.photoduel.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.agency.photoduel.BuildConfig
import com.agency.photoduel.data.remote.RemoteServices
import com.agency.photoduel.domain.port.PhotoDuelService
import com.agency.photoduel.domain.port.ServerClock
import com.agency.photoduel.engine.android.AndroidPhotoPipeline
import com.agency.photoduel.platform.ActivityHost
import com.agency.photoduel.platform.deeplink.InviteLinkInbox
import com.agency.photoduel.platform.network.NetworkMonitor
import com.agency.photoduel.platform.notifications.NotificationPermissionController
import com.agency.photoduel.platform.picking.AndroidPhotoPicking
import com.agency.photoduel.platform.picking.CameraCache
import com.agency.photoduel.platform.picking.CameraCleanupPhotoProcessing
import com.agency.photoduel.platform.session.EncryptedSessionStore
import com.agency.photoduel.platform.sharing.AndroidInvitationSharing
import com.agency.photoduel.ui.PhotoProcessing
import com.agency.photoduel.ui.UiDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * The composition root: the one place that knows every concrete class. PhotoDuelApplication builds
 * exactly one, and nothing else in the app calls a constructor of the data, engine or platform layers.
 *
 * Wiring:
 *  - the session token lives in [EncryptedSessionStore] (Keystore AES-GCM over DataStore), wrapped in
 *    [SessionGate] so that a 401 can clear it safely;
 *  - [RemoteServices] (data layer) talks to [apiBaseUrl] and persists the token through the gate;
 *  - the service handed to the UI is wrapped in [GuardedPhotoDuelService], which clears the stored
 *    session on any 401 so the user lands back on sign-in;
 *  - when [apiBaseUrl] is blank (every release build today) or unusable, [serverConfigured] is false and
 *    the service is [UnconfiguredPhotoDuelService]: nothing touches the network and nothing is faked.
 *
 * No URL is hard-coded here. The value comes from BuildConfig.API_BASE_URL (apps/photoduel/build.gradle.kts).
 */
class AppContainer(
    context: Context,
    apiBaseUrl: String = BuildConfig.API_BASE_URL,
) {
    private val appContext: Context = context.applicationContext

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val diskScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Platform adapters.
    val activityHost = ActivityHost()
    val inviteInbox = InviteLinkInbox()
    val networkMonitor = NetworkMonitor(appContext, applicationScope)
    val notificationPermission = NotificationPermissionController(appContext, activityHost)
    private val cameraCache = CameraCache(appContext, authority = "${appContext.packageName}.fileprovider")
    val photoPicking: AndroidPhotoPicking = AndroidPhotoPicking(activityHost, cameraCache)
    private val sharing = AndroidInvitationSharing(appContext, activityHost)

    // Session storage and the server connection.
    val sessionGate = SessionGate(
        EncryptedSessionStore.create(
            context = appContext,
            scope = diskScope,
            onProblem = { Log.w(TAG, it) },
        ),
    )
    private val remote: RemoteServices? = connect(apiBaseUrl)

    /** False when this build has no usable API base URL. The UI then shows its setup state. */
    val serverConfigured: Boolean = remote != null

    val service: PhotoDuelService =
        GuardedPhotoDuelService(remote?.service ?: UnconfiguredPhotoDuelService, sessionGate)
    val clock: ServerClock = remote?.clock ?: DeviceClockFallback

    /** Emits each time a stored session was cleared because the server answered 401. */
    val sessionExpired: SharedFlow<Unit> get() = sessionGate.expired

    // Photo processing: the engine pipeline, plus deletion of camera files once processing ends.
    private val photoProcessing: PhotoProcessing =
        CameraCleanupPhotoProcessing(AndroidPhotoPipeline(appContext), cameraCache)

    val uiDependencies = UiDependencies(
        service = service,
        clock = clock,
        photoPicking = photoPicking,
        photoProcessing = photoProcessing,
        invitationSharing = sharing,
        pendingInviteTokens = inviteInbox.tokens,
        serverConfigured = serverConfigured,
    )

    private val sessionRefresher = SessionRefresher(
        scope = applicationScope,
        store = sessionGate,
        service = service,
        enabled = serverConfigured,
        monotonicMillis = SystemClock::elapsedRealtime,
    )

    /** Called once from Application.onCreate. */
    fun start() {
        // Camera files left behind by a crash or process death are swept off the main thread.
        diskScope.launch { cameraCache.purgeStale() }
        // Refresh the session every time the app comes to the foreground (not on each activity resume).
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) sessionRefresher.onProcessResumed()
            },
        )
    }

    /** Called from MainActivity.onCreate, before setContent: launchers must exist before STARTED. */
    fun bindActivity(activity: ComponentActivity) {
        activityHost.attach(activity)
        photoPicking.attach(activity)
        notificationPermission.attach(activity)
    }

    /** Called from MainActivity.onDestroy. */
    fun unbindActivity(activity: ComponentActivity) {
        photoPicking.detach(activity)
        notificationPermission.detach(activity)
        activityHost.detach(activity)
    }

    private fun connect(baseUrl: String): RemoteServices? {
        if (baseUrl.isBlank()) return null
        // A release build must never talk plain HTTP, whatever value was injected at build time.
        if (!BuildConfig.DEBUG && !baseUrl.startsWith("https://", ignoreCase = true)) {
            Log.e(TAG, "Refusing a non-HTTPS API base URL in a release build; running as server not configured")
            return null
        }
        return try {
            RemoteServices.create(baseUrl, sessionGate)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "API base URL is not usable; running as server not configured")
            null
        }
    }

    private companion object {
        const val TAG = "PhotoDuel"
    }
}
