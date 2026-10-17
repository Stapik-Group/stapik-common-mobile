package pl.stapik.common.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * AES-256 keys held in the Android Keystore. The key material never leaves the Keystore; the app
 * only gets a handle to use it.
 *
 * Caveat (plan 4.2): if the system drops the key (for example after a restore on another device),
 * data encrypted with it can no longer be read. The only protection is an export (4.6) or the cloud.
 */
object KeystoreKeys {
    private const val PROVIDER = "AndroidKeyStore"

    /** Returns the key stored under [alias], creating it on first use. */
    @Synchronized
    fun getOrCreate(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(spec)
        return generator.generateKey()
    }

    /** Removes the key. Everything encrypted with it becomes unreadable. */
    @Synchronized
    fun delete(alias: String) {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }
}
