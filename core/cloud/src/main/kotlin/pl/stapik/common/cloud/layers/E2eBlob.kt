package pl.stapik.common.cloud.layers

import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.InvalidCipherTextException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pl.stapik.common.sync.CloudError
import pl.stapik.common.sync.CloudException
import pl.stapik.common.sync.UnreadableKind

/**
 * The `stapik-e2e` layer of the desktop format:
 * `{"format":"stapik-e2e","version":1,"salt":"..","nonce":"..","ciphertext":".."}` with standard
 * base64. The associated data is `stapik-e2e/1/<slot>/<context>`, so the server cannot present one
 * document as another. A fresh salt is chosen per instance (like the desktop does per session) and
 * keys are cached per salt, so Argon2 runs once per salt, not once per request.
 *
 * @param scope the slot key.
 * @param random source of salt and nonces; replaced in tests to make output deterministic.
 */
internal class E2eBlob(
    private val passphrase: String,
    private val scope: String,
    private val random: (Int) -> ByteArray = secureRandomBytes,
) {
    private val writeSalt: ByteArray = random(XChaChaBox.SALT_BYTES)
    private val keys = LinkedHashMap<String, ByteArray>()

    @Synchronized
    private fun keyFor(salt: ByteArray): ByteArray {
        val id = Base64.getEncoder().encodeToString(salt)
        keys[id]?.let { return it }
        if (keys.size >= MAX_CACHED_KEYS) {
            val own = Base64.getEncoder().encodeToString(writeSalt)
            keys.keys.removeAll { it != own }
        }
        return XChaChaBox.deriveKey(passphrase, salt).also { keys[id] = it }
    }

    private fun associatedData(context: String): ByteArray = "$FORMAT/$VERSION/$scope/$context".toByteArray(Charsets.UTF_8)

    fun seal(plain: String, context: String): String {
        val nonce = random(XChaChaBox.NONCE_BYTES)
        val encrypted = XChaChaBox.seal(keyFor(writeSalt), nonce, associatedData(context), plain.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return JsonObject(
            mapOf(
                "format" to JsonPrimitive(FORMAT),
                "version" to JsonPrimitive(VERSION),
                "salt" to JsonPrimitive(encoder.encodeToString(writeSalt)),
                "nonce" to JsonPrimitive(encoder.encodeToString(nonce)),
                "ciphertext" to JsonPrimitive(encoder.encodeToString(encrypted)),
            ),
        ).toString()
    }

    /** Decrypts a blob. Throws [CloudException] with [CloudError.Unreadable] on any failure. */
    fun open(blob: JsonObject, context: String): String {
        val version = (blob["version"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: throw unreadable("The encrypted cloud data is damaged")
        if (version != VERSION) {
            throw unreadable("The cloud data was encrypted by a newer version of the application", UnreadableKind.UNSUPPORTED_VERSION)
        }
        val salt = bytesField(blob, "salt")
        val nonce = bytesField(blob, "nonce")
        val ciphertext = bytesField(blob, "ciphertext")
        if (salt.size != XChaChaBox.SALT_BYTES || nonce.size != XChaChaBox.NONCE_BYTES || ciphertext.size < XChaChaBox.TAG_BYTES) {
            throw unreadable("The encrypted cloud data is damaged")
        }
        return try {
            XChaChaBox.open(keyFor(salt), nonce, associatedData(context), ciphertext).toString(Charsets.UTF_8)
        } catch (e: InvalidCipherTextException) {
            throw unreadable("The passphrase is wrong or the cloud data was modified", UnreadableKind.DECRYPTION_FAILED)
        }
    }

    private fun bytesField(blob: JsonObject, name: String): ByteArray {
        val text = JsonProbe.string(blob, name) ?: throw unreadable("The encrypted cloud data is damaged")
        return try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            throw unreadable("The encrypted cloud data is damaged")
        }
    }

    private fun unreadable(reason: String, kind: UnreadableKind = UnreadableKind.OTHER) =
        CloudException(CloudError.Unreadable(reason, kind))

    companion object {
        const val FORMAT = "stapik-e2e"
        const val VERSION = 1
        private const val MAX_CACHED_KEYS = 8
        private val secureRandom = SecureRandom()
        private val secureRandomBytes: (Int) -> ByteArray = { size -> ByteArray(size).also { secureRandom.nextBytes(it) } }

        /** The blob as an object if the text is a `stapik-e2e` blob, else null. */
        fun parse(text: String): JsonObject? {
            if (!text.contains(FORMAT)) return null
            val root = JsonProbe.parseObject(text) ?: return null
            return root.takeIf { JsonProbe.string(it, "format") == FORMAT }
        }
    }
}
