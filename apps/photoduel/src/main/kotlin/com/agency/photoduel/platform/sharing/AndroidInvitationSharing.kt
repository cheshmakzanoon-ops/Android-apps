package com.agency.photoduel.platform.sharing

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import com.agency.photoduel.platform.ActivityHost
import com.agency.photoduel.platform.PlatformTexts
import com.agency.photoduel.ui.InvitationSharing

/**
 * Opens the system share sheet (`ACTION_SEND`, `text/plain`) with a sentence that names the room and
 * carries the invitation URL exactly as given. The only link form in this build is
 * `photoduel://invite/{token}`; App Links are not claimed. Many chat apps do not make a custom-scheme
 * link tappable, which is why the text says to open it on a phone that has the app, and why a
 * clipboard copy is offered as a fallback ([copyToClipboard], also used if no share target exists).
 *
 * The invitation URL is a bearer capability, so the clipboard entry is flagged sensitive (API 33+) to
 * keep it out of the clipboard preview.
 */
class AndroidInvitationSharing(
    private val appContext: Context,
    private val host: ActivityHost,
) : InvitationSharing {

    override fun share(roomName: String, inviteUrl: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, PlatformTexts.SHARE_SUBJECT)
            putExtra(Intent.EXTRA_TEXT, InvitationText.build(roomName, inviteUrl))
        }
        val chooser = Intent.createChooser(send, null)
        try {
            val activity = host.current()
            if (activity != null) {
                activity.startActivity(chooser)
            } else {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(chooser)
            }
        } catch (e: ActivityNotFoundException) {
            copyToClipboard(roomName, inviteUrl)
        } catch (e: SecurityException) {
            copyToClipboard(roomName, inviteUrl)
        }
    }

    /** Puts the same invitation text on the clipboard. Returns false if the clipboard is unavailable. */
    fun copyToClipboard(roomName: String, inviteUrl: String): Boolean {
        val clipboard = appContext.getSystemService(ClipboardManager::class.java) ?: return false
        val clip = ClipData.newPlainText(
            PlatformTexts.CLIPBOARD_LABEL,
            InvitationText.build(roomName, inviteUrl),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        return try {
            clipboard.setPrimaryClip(clip)
            // Android 13 and later show their own "copied" confirmation.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(appContext, PlatformTexts.INVITATION_COPIED, Toast.LENGTH_SHORT).show()
            }
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
