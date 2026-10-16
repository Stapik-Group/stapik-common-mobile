package pl.stapik.common.document

/**
 * Encrypts and decrypts the bytes of a stored file (encryption at rest).
 *
 * [decrypt] must throw when the data cannot be decrypted (wrong or lost key, tampering); the store
 * then treats the file as unreadable and falls back to a backup instead of returning garbage.
 */
interface PayloadCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(encrypted: ByteArray): ByteArray

    /** Stores bytes as they are. For tests and for data that needs no protection. */
    object None : PayloadCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain

        override fun decrypt(encrypted: ByteArray): ByteArray = encrypted
    }
}
