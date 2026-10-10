package com.agency.stickerjet.data

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.agency.stickerjet.domain.ImportOutcome
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Imports a clip the user picked into a new project. The clip is copied into app-private storage first,
 * so the original URI is not needed again after the import.
 *
 * Steps: size and storage checks, a size-capped copy to `source.mp4`, a probe, the [ImportPolicy]
 * judgement, an optional thumbnail, then the save. Every outcome other than [ImportOutcome.Imported]
 * leaves no project folder behind.
 *
 * [projectId] must be a new id. An id that already has a project document is refused with
 * [IllegalArgumentException]. The picked file's name is read once, used as the project title, and not
 * stored anywhere else.
 */
class ImportService(
    private val resolver: ContentResolver,
    files: ProjectFiles,
    clock: () -> Long,
) {
    private val pipeline = ImportPipeline(
        files = files,
        probe = MediaMetadataProbe(),
        storage = StorageGuard(files.root),
        repository = FileProjectRepository(files, ProjectEnvelope.createJson(), clock),
        clock = clock,
    )

    /**
     * Imports the clip at [uri] as project [projectId]. The I/O runs on Dispatchers.IO. Revoked access
     * and vanished files come back as [ImportOutcome.Failed], not as exceptions.
     */
    suspend fun import(uri: Uri, projectId: String): ImportOutcome =
        pipeline.run(projectId, ResolverClipSource(resolver, uri))
}

/** [ClipSource] over a content URI. Each call goes to the provider, so revoked access shows up when it happens. */
internal class ResolverClipSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : ClipSource {

    /**
     * Reads the size and the display name in one query. If the query or the cursor fails, or returns
     * nothing, the result is an unknown size and an empty name. Access problems then surface when the
     * stream opens.
     */
    override fun describe(): ClipInfo {
        val info = try {
            readRow()
        } catch (e: RuntimeException) {
            null
        }
        return info ?: ClipInfo(sizeBytes = null, displayName = "")
    }

    private fun readRow(): ClipInfo? {
        val cursor = queryOrNull() ?: return null
        return cursor.use { row ->
            if (!row.moveToFirst()) {
                null
            } else {
                ClipInfo(
                    sizeBytes = row.longOrNull(OpenableColumns.SIZE)?.takeIf { it >= 0 },
                    displayName = row.stringOrNull(OpenableColumns.DISPLAY_NAME).orEmpty(),
                )
            }
        }
    }

    override fun openStream(): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException("The provider returned no stream")

    /** The metadata query. Any failure means "unknown", so the copy step decides what it means. */
    private fun queryOrNull(): Cursor? {
        val projection: Array<String> = arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME)
        return try {
            resolver.query(uri, projection, null, null, null)
        } catch (e: RuntimeException) {
            null
        } catch (e: IOException) {
            null
        }
    }
}

private fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getLong(index)
}

private fun Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getString(index)
}
