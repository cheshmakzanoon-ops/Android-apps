package com.agency.timeflip.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Must match the provider authority declared in AndroidManifest.xml ("${applicationId}.exports"). */
fun exportsAuthority(context: Context): String = "${context.packageName}.exports"

/** MIME type of every exported file. */
const val EXPORT_MIME_TYPE: String = "video/mp4"

/**
 * Builds the share chooser for an exported MP4 without starting it.
 *
 * The file is shared from its real location (filesDir/timeflip/exports, see file_paths.xml), so no
 * copy is made. The grant is read-only and only lasts for the receiving app's session. TimeFlip never
 * chooses a destination app: the system chooser shows what the user has installed.
 *
 * @throws IllegalArgumentException when [file] is outside the paths declared in file_paths.xml.
 *   That is a programming error, not user input.
 */
fun buildVideoShareIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, exportsAuthority(context), file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = EXPORT_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, "TimeFlip video", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, null).apply {
        // The chooser must carry the same grant, or the target app cannot read the stream.
        clipData = send.clipData
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * Opens the system share sheet for [file].
 *
 * @return false when the file cannot be shared or no activity can handle the chooser, so the UI can
 *   show a message. Nothing is logged: the path and the URI are private.
 */
fun shareVideo(context: Context, file: File): Boolean =
    try {
        context.startActivity(buildVideoShareIntent(context, file))
        true
    } catch (notFound: ActivityNotFoundException) {
        false
    } catch (notShareable: IllegalArgumentException) {
        false
    }
