package pl.stapik.common.document

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [PayloadCipher] with AES-256-GCM.
 *
 * Output layout: `[1 byte format version][12 byte IV][ciphertext + 16 byte tag]`. A fresh random IV
 * is produced by the crypto provider for every call, so the same data never encrypts to the same bytes.
 *
 * [associatedData] is authenticated but not stored; use something that names the purpose of the
 * file (for example the slot), so that a file copied over another one fails to decrypt.
 *
 * [keyProvider] returns the key; on Android it comes from the Keystore (module `core:crypto`),
 * in tests it is a plain generated key. A wrong or missing key makes [decrypt] throw.
 */
class AesGcmCipher(
    private val associatedData: ByteArray = ByteArray(0),
    private val keyProvider: () -> SecretKey,
) : PayloadCipher {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No IV is passed: the provider generates a random one (required by the Android Keystore).
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        val encrypted = cipher.doFinal(plain)
        val iv = cipher.iv
        check(iv != null && iv.size == IV_BYTES) { "Unexpected IV length" }
        return byteArrayOf(FORMAT_VERSION) + iv + encrypted
    }

    override fun decrypt(encrypted: ByteArray): ByteArray {
        require(encrypted.size >= HEADER_BYTES + TAG_BYTES) { "Encrypted data is too short" }
        require(encrypted[0] == FORMAT_VERSION) { "Unsupported encryption format" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BYTES * 8, encrypted, 1, IV_BYTES))
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        return cipher.doFinal(encrypted, HEADER_BYTES, encrypted.size - HEADER_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val HEADER_BYTES = 1 + IV_BYTES
    }
}
