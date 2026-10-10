package com.agency.stickerjet.platform

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Shares one exported sticker as a file, never as a path.
 *
 * The export lives in app-private storage, which no other app may read. [shareIntent] therefore
 * copies it into `cacheDir/share/` and exposes only that directory through the FileProvider declared
 * in the manifest (see `res/xml/file_paths.xml`). The copy is temporary: [clearShareCopies] removes
 * every copy at process start, and Android may also evict the cache under storage pressure.
 *
 * Grants: the read grant is set on the send intent and on the chooser, so it reaches the receiving
 * app whichever one the user picks. The provider is not exported; access works only through grants.
 * Nothing here claims that a messenger "installs" the sticker. The receiver gets a file to save or
 * forward.
 */
object ExportSharing {
    const val MIME_TYPE_WEBP: String = "image/webp"

    /** Directory name under cacheDir that the FileProvider exposes. Must match file_paths.xml. */
    internal const val SHARE_DIR_NAME: String = "share"

    /**
     * Copies [file] into the share directory under its own file name and returns a chooser intent.
     * The same export always maps to the same share copy, so repeated shares overwrite it.
     *
     * @throws FileNotFoundException when [file] does not exist. The caller shows a retry or cancel path.
     */
    fun shareIntent(context: Context, file: File): Intent {
        if (!file.isFile) throw FileNotFoundException("Export missing: ${file.name}")

        val shareDir = File(context.cacheDir, SHARE_DIR_NAME).apply { mkdirs() }
        val copy = File(shareDir, file.name)
        file.copyTo(copy, overwrite = true)

        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, copy)

        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE_WEBP
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // createChooser copies ClipData and grant flags in recent platforms. Setting them again on
        // the chooser keeps the grant working on every API level from minSdk upward.
        return Intent.createChooser(send, null).apply {
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Deletes every temporary share copy. Call once at process start, before the first share.
     * Failures are ignored on purpose: a leftover copy is still inside app-private cache.
     */
    fun clearShareCopies(context: Context) {
        File(context.cacheDir, SHARE_DIR_NAME).deleteRecursively()
    }
}
