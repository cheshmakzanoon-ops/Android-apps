package com.agency.stickerjet.platform

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An import the user started that has not finished yet.
 *
 * [uri] is the content URI string from the picker. It can be stale after process death, so a
 * restored value must be treated as "maybe revoked". [projectId] names the project directory the
 * import writes into, so a retry reuses the same directory instead of leaving a second one behind.
 */
data class PendingImport(
    val uri: String,
    val projectId: String,
    val startedAtMs: Long,
)

/**
 * Remembers the import in progress so that a restart after process death can finish or fail it.
 *
 * Storage: a private SharedPreferences file. The app sets allowBackup=false, so this record never
 * leaves the device. It holds no media bytes, only the URI string and the project id.
 *
 * Durability: [save] uses commit(), so the record is on disk before the copy starts. An apply()
 * could be lost if the process dies in the next few milliseconds. All calls run on Dispatchers.IO.
 *
 * Use: `save` before `ImportService.import`, `clear` after an Imported, Rejected or Failed outcome,
 * and `load` in the composition root or the ViewModel's init on a cold start. When [load] returns a
 * record, retry the import or report ACCESS_REVOKED. Do not assume the URI still works.
 */
class PendingImportHolder(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Stores [pending] synchronously. Returns false when the record could not be written. */
    suspend fun save(pending: PendingImport): Boolean = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_URI, pending.uri)
            .putString(KEY_PROJECT_ID, pending.projectId)
            .putLong(KEY_STARTED_AT_MS, pending.startedAtMs)
            .commit()
    }

    /** Returns the stored import, or null when none is pending or the record is incomplete. */
    suspend fun load(): PendingImport? = withContext(Dispatchers.IO) {
        val uri = prefs.getString(KEY_URI, null) ?: return@withContext null
        val projectId = prefs.getString(KEY_PROJECT_ID, null) ?: return@withContext null
        PendingImport(
            uri = uri,
            projectId = projectId,
            startedAtMs = prefs.getLong(KEY_STARTED_AT_MS, 0L),
        )
    }

    /** Removes the record. Returns false when the write could not be committed. */
    suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val FILE_NAME = "stickerjet_pending_import"
        const val KEY_URI = "uri"
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_STARTED_AT_MS = "started_at_ms"
    }
}
