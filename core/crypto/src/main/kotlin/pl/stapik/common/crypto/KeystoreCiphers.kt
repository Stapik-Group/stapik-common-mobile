package pl.stapik.common.crypto

import pl.stapik.common.document.AesGcmCipher
import pl.stapik.common.document.PayloadCipher

/** Ready-made [PayloadCipher]s backed by the Android Keystore. */
object KeystoreCiphers {
    /**
     * @param alias Keystore alias; use one alias per app (and a different one than for the
     *   connection settings), so the data key can be handled separately.
     * @param associatedData binds the ciphertext to its purpose (for example the slot name).
     */
    fun create(alias: String, associatedData: ByteArray = ByteArray(0)): PayloadCipher {
        val key by lazy { KeystoreKeys.getOrCreate(alias) }
        return AesGcmCipher(associatedData) { key }
    }
}
