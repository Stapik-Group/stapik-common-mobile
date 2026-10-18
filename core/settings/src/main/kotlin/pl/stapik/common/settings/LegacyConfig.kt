package pl.stapik.common.settings

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import pl.stapik.common.cloud.CloudConfig

/** The saved preferences of an app's old connection storage: raw string values by key. */
interface LegacyPrefsSource {
    fun readAll(): Map<String, String>

    /** Removes everything the old storage held. */
    fun clear()
}

/** Decrypts one value the way the old `CryptoManager` encrypted it. Throws when it cannot. */
fun interface LegacyDecryptor {
    fun decrypt(iv: ByteArray, cipherText: ByteArray): ByteArray
}

/**
 * The old apps' scheme: AES-256-GCM, 128-bit tag, no associated data, key under a Keystore alias of
 * the app. [key] returns that key, or null when the alias does not exist (nothing to migrate).
 */
class AesGcmLegacyDecryptor(private val key: () -> SecretKey?) : LegacyDecryptor {
    override fun decrypt(iv: ByteArray, cipherText: ByteArray): ByteArray {
        val secret = key() ?: throw java.security.GeneralSecurityException("The old key is gone")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
        return cipher.doFinal(cipherText)
    }
}

/** How an app stored the connection settings before this library. */
enum class LegacyFormat {
    /**
     * calendar-mobile and planner-mobile: DataStore `api_config` with `base_url_cipher`,
     * `base_url_iv`, `api_key_cipher`, `api_key_iv`, each Base64.
     */
    CALENDAR_PLANNER,

    /**
     * media-mobile and budgeting-mobile: DataStore `connection_config` with `server_url_enc` and
     * `api_key_enc`, each `Base64(iv):Base64(ciphertext)`.
     */
    MEDIA_BUDGETING,
}

/**
 * [LegacyConfigReader] over an old DataStore, reached through [source]. Anything unreadable
 * (missing value, lost key, damaged text) is "no old configuration", never an exception: the user
 * then connects again, exactly as the old app would have asked.
 */
class LegacyPrefsConfigReader(
    private val source: LegacyPrefsSource,
    private val format: LegacyFormat,
    private val decryptor: LegacyDecryptor,
) : LegacyConfigReader {

    override fun read(): CloudConfig? = try {
        val prefs = source.readAll()
        when (format) {
            LegacyFormat.CALENDAR_PLANNER -> CloudConfig(
                apiUrl = decryptPair(prefs["base_url_cipher"], prefs["base_url_iv"]),
                apiKey = decryptPair(prefs["api_key_cipher"], prefs["api_key_iv"]),
            )
            LegacyFormat.MEDIA_BUDGETING -> CloudConfig(
                apiUrl = decryptJoined(prefs["server_url_enc"]),
                apiKey = decryptJoined(prefs["api_key_enc"]),
            )
        }
    } catch (e: Exception) {
        null
    }

    override fun discard() = source.clear()

    private fun decryptPair(cipherText: String?, iv: String?): String =
        decode(requireNotNull(iv), requireNotNull(cipherText))

    private fun decryptJoined(value: String?): String {
        val parts = requireNotNull(value).split(":", limit = 2)
        require(parts.size == 2)
        return decode(parts[0], parts[1])
    }

    private fun decode(iv: String, cipherText: String): String {
        val decoder = Base64.getMimeDecoder()
        return String(decryptor.decrypt(decoder.decode(iv), decoder.decode(cipherText)), Charsets.UTF_8)
    }
}
