package com.agency.photoduel.platform.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.agency.photoduel.domain.port.SessionStore
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The bearer token, sealed with [TokenCipher] and kept in a DataStore Preferences file under the app's
 * private files directory (`files/datastore/photoduel_session.preferences_pb`). Only ciphertext is ever
 * written. The file is excluded from cloud backup and device transfer (res/xml), and allowBackup is off.
 *
 * Failure policy: a missing, corrupted or unreadable session is reported as "signed out" (null). It is
 * never an exception and never a crash. If the token cannot be persisted, the sign-in still works for
 * this process and the user is simply signed out at the next launch.
 *
 * All access is serialized and an in-memory copy avoids a decrypt on every request.
 */
class EncryptedSessionStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
    private val onProblem: (String) -> Unit = {},
) : SessionStore {

    private val mutex = Mutex()
    private var loaded = false
    private var cached: String? = null

    override suspend fun read(): String? = mutex.withLock {
        if (!loaded) {
            cached = loadFromDisk()
            loaded = true
        }
        cached
    }

    override suspend fun write(token: String?) {
        mutex.withLock {
            val value = token?.takeIf { it.isNotEmpty() }
            cached = value
            loaded = true
            try {
                if (value == null) {
                    dataStore.edit { it.remove(KEY_SESSION) }
                } else {
                    val sealed = seal(value)
                    dataStore.edit { it[KEY_SESSION] = sealed }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onProblem("session could not be persisted (${e.javaClass.simpleName}); it will not survive a restart")
                // Fail closed: never leave an older token on disk that this process no longer uses.
                removeStoredSessionQuietly()
            }
        }
    }

    private suspend fun loadFromDisk(): String? {
        val stored: String? = try {
            dataStore.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .first()[KEY_SESSION]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onProblem("session file unreadable (${e.javaClass.simpleName}); treating as signed out")
            return null
        }
        if (stored == null) return null
        return try {
            String(cipher.decrypt(Base64.getDecoder().decode(stored)), Charsets.UTF_8)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TokenCipherException) {
            onProblem("stored session unusable (${e.kind}); treating as signed out")
            // Corrupt bytes can never be read again, so drop them. A merely unavailable key may be
            // transient, so the bytes are left alone and the next sign-in overwrites them.
            if (e.kind == TokenCipherException.Kind.CORRUPT) removeStoredSessionQuietly()
            null
        } catch (e: IllegalArgumentException) {
            onProblem("stored session is not valid Base64; treating as signed out")
            removeStoredSessionQuietly()
            null
        } catch (e: Exception) {
            onProblem("stored session unusable (${e.javaClass.simpleName}); treating as signed out")
            null
        }
    }

    private fun seal(token: String): String {
        val plain = token.toByteArray(Charsets.UTF_8)
        val blob = try {
            cipher.encrypt(plain)
        } catch (e: TokenCipherException) {
            // A broken or missing key is replaced once; a second failure propagates to write().
            cipher.resetKey()
            cipher.encrypt(plain)
        }
        return Base64.getEncoder().encodeToString(blob)
    }

    private suspend fun removeStoredSessionQuietly() {
        try {
            dataStore.edit { it.remove(KEY_SESSION) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort only.
        }
    }

    companion object {
        const val DEFAULT_FILE_NAME = "photoduel_session.preferences_pb"

        /** Relative to `Context.filesDir`. The backup rules in res/xml name this exact path. */
        const val DIRECTORY = "datastore"

        private val KEY_SESSION = stringPreferencesKey("session_v1")

        /**
         * Builds the production store. DataStore allows one instance per file per process, so the app
         * creates it once (AppContainer). A corrupt preferences file is replaced by an empty one.
         */
        fun create(
            context: Context,
            scope: CoroutineScope,
            cipher: TokenCipher = AesGcmTokenCipher(AndroidKeystoreKeySource()),
            fileName: String = DEFAULT_FILE_NAME,
            onProblem: (String) -> Unit = {},
        ): EncryptedSessionStore {
            val filesDir = context.applicationContext.filesDir
            val dataStore = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
            ) { File(File(filesDir, DIRECTORY), fileName) }
            return EncryptedSessionStore(dataStore, cipher, onProblem)
        }
    }
}
