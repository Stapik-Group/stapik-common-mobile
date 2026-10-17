package pl.stapik.common.cloud.layers

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * The primitives of `stapik-e2e`, bit-compatible with libsodium as used by the desktop:
 *
 * - key from passphrase: `crypto_pwhash` Argon2id v1.3, "interactive" limits (2 passes, 64 MiB,
 *   1 lane), 16-byte salt, 32-byte key;
 * - `crypto_aead_xchacha20poly1305_ietf`: HChaCha20 derives a subkey from the key and the first
 *   16 nonce bytes, then ChaCha20-Poly1305 (RFC 8439) runs with a 12-byte nonce made of four zero
 *   bytes and the last 8 nonce bytes. Output is ciphertext followed by the 16-byte tag.
 *
 * Implemented on BouncyCastle (pure Java) instead of a native libsodium wrapper, so it behaves the
 * same on every device and is covered by JVM tests against vectors produced by real libsodium.
 */
internal object XChaChaBox {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 24
    const val SALT_BYTES = 16
    const val TAG_BYTES = 16

    private const val ARGON2_PASSES = 2
    private const val ARGON2_MEMORY_KIB = 65536

    fun deriveKey(passphrase: String, salt: ByteArray): ByteArray {
        require(salt.size == SALT_BYTES) { "Salt must be $SALT_BYTES bytes" }
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(ARGON2_PASSES)
            .withMemoryAsKB(ARGON2_MEMORY_KIB)
            .withParallelism(1)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(parameters)
        val key = ByteArray(KEY_BYTES)
        generator.generateBytes(passphrase.toByteArray(Charsets.UTF_8), key, 0, KEY_BYTES)
        return key
    }

    /** Returns ciphertext followed by the tag. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val cipher = newCipher(true, key, nonce, aad)
        val out = ByteArray(cipher.getOutputSize(plain.size))
        var length = cipher.processBytes(plain, 0, plain.size, out, 0)
        length += cipher.doFinal(out, length)
        return if (length == out.size) out else out.copyOf(length)
    }

    /** Throws [org.bouncycastle.crypto.InvalidCipherTextException] when authentication fails. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, cipherAndTag: ByteArray): ByteArray {
        val cipher = newCipher(false, key, nonce, aad)
        val out = ByteArray(cipher.getOutputSize(cipherAndTag.size))
        var length = cipher.processBytes(cipherAndTag, 0, cipherAndTag.size, out, 0)
        length += cipher.doFinal(out, length)
        return if (length == out.size) out else out.copyOf(length)
    }

    private fun newCipher(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray): ChaCha20Poly1305 {
        require(key.size == KEY_BYTES) { "Key must be $KEY_BYTES bytes" }
        require(nonce.size == NONCE_BYTES) { "Nonce must be $NONCE_BYTES bytes" }
        val subKey = hChaCha20(key, nonce.copyOfRange(0, 16))
        val chachaNonce = ByteArray(12)
        System.arraycopy(nonce, 16, chachaNonce, 4, 8)
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(subKey), TAG_BYTES * 8, chachaNonce, aad))
        return cipher
    }

    private fun hChaCha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865
        s[1] = 0x3320646e
        s[2] = 0x79622d32
        s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, i * 4)
        for (i in 0 until 4) s[12 + i] = le32(nonce16, i * 4)
        repeat(10) {
            quarterRound(s, 0, 4, 8, 12)
            quarterRound(s, 1, 5, 9, 13)
            quarterRound(s, 2, 6, 10, 14)
            quarterRound(s, 3, 7, 11, 15)
            quarterRound(s, 0, 5, 10, 15)
            quarterRound(s, 1, 6, 11, 12)
            quarterRound(s, 2, 7, 8, 13)
            quarterRound(s, 3, 4, 9, 14)
        }
        val out = ByteArray(32)
        for (i in 0 until 4) putLe32(out, i * 4, s[i])
        for (i in 0 until 4) putLe32(out, 16 + i * 4, s[12 + i])
        return out
    }

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
    }

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun putLe32(out: ByteArray, offset: Int, value: Int) {
        out[offset] = value.toByte()
        out[offset + 1] = (value ushr 8).toByte()
        out[offset + 2] = (value ushr 16).toByte()
        out[offset + 3] = (value ushr 24).toByte()
    }
}
