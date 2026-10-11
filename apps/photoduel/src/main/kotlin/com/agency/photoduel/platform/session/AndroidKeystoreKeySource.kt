package com.agency.photoduel.platform.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A 256-bit AES key that lives in the Android Keystore and never leaves it. The key is created on first
 * use, needs no user authentication (the token must be readable in the background), and cannot be
 * exported. It is not part of any backup, which is why the ciphertext is excluded from backups too.
 */
class AndroidKeystoreKeySource(private val alias: String = DEFAULT_ALIAS) : SecretKeySource {
    private val lock = Any()

    override fun key(): SecretKey = synchronized(lock) {
        val store = loadStore()
        (store.getKey(alias, null) as? SecretKey) ?: generate()
    }

    override fun reset() {
        synchronized(lock) {
            val store = loadStore()
            if (store.containsAlias(alias)) store.deleteEntry(alias)
        }
    }

    private fun loadStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun generate(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "photoduel.session.v1"
    }
}
