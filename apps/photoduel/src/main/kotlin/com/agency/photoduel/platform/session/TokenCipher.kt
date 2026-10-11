package com.agency.photoduel.platform.session

import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Why a stored value could not be turned back into a token. */
class TokenCipherException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind {
        /** The bytes are not a value this cipher wrote with the current key. They can never be read. */
        CORRUPT,

        /** The key could not be loaded or used. May be transient; the stored bytes may still be fine. */
        KEY_UNAVAILABLE,
    }
}

/** Seals and opens the session token. Implementations throw [TokenCipherException] and nothing else. */
interface TokenCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray

    /** Forgets the current key so that the next encrypt creates a fresh one. */
    fun resetKey()
}

/** Supplies the AES key. On Android it is backed by the Keystore; JVM tests use an in-memory key. */
interface SecretKeySource {
    fun key(): SecretKey
    fun reset()
}

/**
 * AES-256-GCM with a fresh random 96-bit IV for every encryption.
 *
 * Blob layout: `version(1) | iv(12) | ciphertext+tag(16)`. The associated data binds a blob to this
 * purpose, so a ciphertext copied from another field of the app does not open.
 *
 * The IV is never chosen by the caller: the provider generates it on `init` (the Android Keystore
 * refuses caller supplied IVs for randomized-encryption keys) and it is read back from the cipher.
 */
class AesGcmTokenCipher(
    private val keys: SecretKeySource,
    private val associatedData: ByteArray = DEFAULT_ASSOCIATED_DATA,
) : TokenCipher {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val key = loadKey()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(associatedData)
            val iv = cipher.iv
            if (iv == null || iv.size != IV_BYTES) {
                throw TokenCipherException(TokenCipherException.Kind.KEY_UNAVAILABLE, "unexpected IV length")
            }
            val body = cipher.doFinal(plaintext)
            val out = ByteArray(1 + IV_BYTES + body.size)
            out[0] = VERSION
            System.arraycopy(iv, 0, out, 1, IV_BYTES)
            System.arraycopy(body, 0, out, 1 + IV_BYTES, body.size)
            return out
        } catch (e: TokenCipherException) {
            throw e
        } catch (e: Exception) {
            throw TokenCipherException(TokenCipherException.Kind.KEY_UNAVAILABLE, "encrypt failed", e)
        }
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size < 1 + IV_BYTES + TAG_BYTES || blob[0] != VERSION) {
            throw TokenCipherException(TokenCipherException.Kind.CORRUPT, "unknown blob layout")
        }
        val key = loadKey()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, blob, 1, IV_BYTES))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
        } catch (e: AEADBadTagException) {
            throw TokenCipherException(TokenCipherException.Kind.CORRUPT, "authentication failed", e)
        } catch (e: BadPaddingException) {
            throw TokenCipherException(TokenCipherException.Kind.CORRUPT, "authentication failed", e)
        } catch (e: IllegalBlockSizeException) {
            throw TokenCipherException(TokenCipherException.Kind.CORRUPT, "bad block size", e)
        } catch (e: GeneralSecurityException) {
            throw TokenCipherException(TokenCipherException.Kind.KEY_UNAVAILABLE, "decrypt failed", e)
        } catch (e: RuntimeException) {
            throw TokenCipherException(TokenCipherException.Kind.KEY_UNAVAILABLE, "decrypt failed", e)
        }
    }

    override fun resetKey() {
        try {
            keys.reset()
        } catch (e: Exception) {
            // Nothing more can be done; the next encrypt reports the failure.
        }
    }

    private fun loadKey(): SecretKey = try {
        keys.key()
    } catch (e: Exception) {
        throw TokenCipherException(TokenCipherException.Kind.KEY_UNAVAILABLE, "key unavailable", e)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val VERSION: Byte = 1
        val DEFAULT_ASSOCIATED_DATA: ByteArray = "photoduel.session.v1".toByteArray(Charsets.UTF_8)
    }
}
