package pl.stapik.common.settings

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The encrypting side below repeats, line for line, what the old `CryptoManager`s did. */
class LegacyConfigTest {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private class Prefs(var values: Map<String, String>) : LegacyPrefsSource {
        var cleared = false
        override fun readAll() = values
        override fun clear() { cleared = true; values = emptyMap() }
    }

    /** Returns (cipher text, iv) as Base64, like `CryptoManager.encrypt` of calendar and planner. */
    private fun encryptPair(text: String, with: SecretKey = key): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, with) }
        val bytes = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(bytes) to Base64.getEncoder().encodeToString(cipher.iv)
    }

    /** `Base64(iv):Base64(ciphertext)`, like media and budgeting (Android `NO_WRAP` = Java basic encoder). */
    private fun encryptJoined(text: String, with: SecretKey = key): String {
        val (cipherText, iv) = encryptPair(text, with)
        return "$iv:$cipherText"
    }

    private fun calendarPrefs(url: String, apiKey: String, with: SecretKey = key): Map<String, String> {
        val (uc, ui) = encryptPair(url, with)
        val (kc, ki) = encryptPair(apiKey, with)
        return mapOf("base_url_cipher" to uc, "base_url_iv" to ui, "api_key_cipher" to kc, "api_key_iv" to ki)
    }

    private fun mediaPrefs(url: String, apiKey: String, with: SecretKey = key) =
        mapOf("server_url_enc" to encryptJoined(url, with), "api_key_enc" to encryptJoined(apiKey, with))

    private fun reader(prefs: Prefs, format: LegacyFormat, k: SecretKey? = key) =
        LegacyPrefsConfigReader(prefs, format, AesGcmLegacyDecryptor { k })

    @Test
    fun `calendar and planner format`() {
        val config = reader(Prefs(calendarPrefs("https://cloud.example/", "key żółć")), LegacyFormat.CALENDAR_PLANNER).read()!!
        assertEquals("https://cloud.example/", config.apiUrl)
        assertEquals("key żółć", config.apiKey)
    }

    @Test
    fun `media and budgeting format`() {
        val config = reader(Prefs(mediaPrefs("https://cloud.example", "k-1")), LegacyFormat.MEDIA_BUDGETING).read()!!
        assertEquals("https://cloud.example", config.apiUrl)
        assertEquals("k-1", config.apiKey)
    }

    @Test
    fun `the wrong format does not read`() {
        assertNull(reader(Prefs(mediaPrefs("https://a.b", "k")), LegacyFormat.CALENDAR_PLANNER).read())
        assertNull(reader(Prefs(calendarPrefs("https://a.b", "k")), LegacyFormat.MEDIA_BUDGETING).read())
    }

    @Test
    fun `nothing stored`() {
        assertNull(reader(Prefs(emptyMap()), LegacyFormat.MEDIA_BUDGETING).read())
        assertNull(reader(Prefs(emptyMap()), LegacyFormat.CALENDAR_PLANNER).read())
    }

    @Test
    fun `a lost key gives no configuration`() {
        val prefs = Prefs(mediaPrefs("https://a.b", "k"))
        assertNull(reader(prefs, LegacyFormat.MEDIA_BUDGETING, k = null).read())
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(reader(prefs, LegacyFormat.MEDIA_BUDGETING, k = other).read())
    }

    @Test
    fun `damaged values give no configuration`() {
        val damaged = mediaPrefs("https://a.b", "k").mapValues { "###:###" }
        assertNull(reader(Prefs(damaged), LegacyFormat.MEDIA_BUDGETING).read())
        assertNull(reader(Prefs(mapOf("server_url_enc" to "nocolon", "api_key_enc" to "nocolon")), LegacyFormat.MEDIA_BUDGETING).read())
    }

    @Test
    fun `discard clears the old storage`() {
        val prefs = Prefs(calendarPrefs("https://a.b", "k"))
        reader(prefs, LegacyFormat.CALENDAR_PLANNER).discard()
        assertTrue(prefs.cleared)
    }

    @Test
    fun `the store migrates an old configuration end to end`() {
        val dir = java.nio.file.Files.createTempDirectory("legacy")
        val prefs = Prefs(mediaPrefs("https://cloud.example/", "secret"))
        val appKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = ConnectionConfigStore(
            dir,
            pl.stapik.common.document.AesGcmCipher { appKey },
            schemaVersion = 1,
            legacy = reader(prefs, LegacyFormat.MEDIA_BUDGETING),
        )
        val first = store.load() as ConfigLoad.Loaded
        assertTrue(first.migratedFromLegacy)
        assertEquals("secret", first.config.apiKey)
        assertTrue(prefs.cleared)
        assertEquals("secret", (store.load() as ConfigLoad.Loaded).config.apiKey)
    }
}
