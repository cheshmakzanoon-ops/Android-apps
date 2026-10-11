package com.agency.photoduel.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.agency.photoduel.platform.deeplink.InviteLink
import com.agency.photoduel.ui.PhotoDuelRoot

/**
 * The single activity. Its jobs are small and deliberate:
 *  1. bind the application container's activity-result launchers BEFORE setContent (Android requires
 *     registration before the activity is STARTED);
 *  2. turn a `photoduel://invite/{token}` link into a validated token on the container's inbox;
 *  3. host the Compose UI (PhotoDuelRoot, written by the UI lane).
 *
 * launchMode is singleTop, so a link opened while the app is in front arrives in [onNewIntent].
 * The activity never keeps a token in a field. After reading an intent it removes the link from it, and
 * it ignores intents that Android replays (restore after rotation or process death, launch from
 * Recents), so a delivered token cannot come back.
 */
class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as PhotoDuelApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container.bindActivity(this)
        // A non-null saved state means this is a re-creation of an activity that already handled its
        // launch intent, so the link in it is stale.
        if (savedInstanceState == null) handleLink(intent)
        setContent { PhotoDuelRoot(container.uiDependencies) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    override fun onDestroy() {
        container.unbindActivity(this)
        super.onDestroy()
    }

    private fun handleLink(incoming: Intent?) {
        if (incoming == null || incoming.action != Intent.ACTION_VIEW) return
        val link = incoming.dataString
        // Whatever happens next, this intent must not deliver the same link again.
        incoming.data = null
        if (incoming.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        // Malformed or foreign links are dropped here; the inbox only ever sees well formed tokens.
        container.inviteInbox.offer(InviteLink.extractToken(link))
    }
}
