package pl.stapik.common.document

import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AesGcmCipherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Serializable
    data class Prefs(val n: Int = 0)

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()
    private fun cipher(aad: String = "notes.json", k: SecretKey = key) = AesGcmCipher(aad.toByteArray()) { k }

    private val plain = "some secret document text".toByteArray()

    @Test
    fun `data round-trips`() {
        val c = cipher()
        assertArrayEquals(plain, c.decrypt(c.encrypt(plain)))
    }

    @Test
    fun `empty data round-trips`() {
        val c = cipher()
        assertArrayEquals(ByteArray(0), c.decrypt(c.encrypt(ByteArray(0))))
    }

    @Test
    fun `output is not the plain text and differs on every call`() {
        val c = cipher()
        val a = c.encrypt(plain)
        val b = c.encrypt(plain)
        assertFalse(String(a, Charsets.ISO_8859_1).contains("secret"))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `a changed byte is detected`() {
        val c = cipher()
        val data = c.encrypt(plain)
        data[data.size - 1] = (data[data.size - 1].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { c.decrypt(data) }
    }

    @Test
    fun `a different key cannot decrypt`() {
        val data = cipher().encrypt(plain)
        assertThrows(Exception::class.java) { cipher(k = newKey()).decrypt(data) }
    }

    @Test
    fun `different associated data cannot decrypt`() {
        val data = cipher(aad = "notes.json").encrypt(plain)
        assertThrows(Exception::class.java) { cipher(aad = "calendar.json").decrypt(data) }
    }

    @Test
    fun `too short or unknown format input is rejected`() {
        val c = cipher()
        assertThrows(IllegalArgumentException::class.java) { c.decrypt(ByteArray(5)) }
        val data = c.encrypt(plain)
        data[0] = 9
        assertThrows(IllegalArgumentException::class.java) { c.decrypt(data) }
    }

    @Test
    fun `works as the cipher of a store and a wrong key is reported as corruption`() = runBlocking {
        val dir: Path = tmp.root.toPath()
        MobileOnlyStore(dir, "prefs", Prefs.serializer(), Prefs(), cipher("prefs")).save(Prefs(9))
        assertEquals(Prefs(9), MobileOnlyStore(dir, "prefs", Prefs.serializer(), Prefs(), cipher("prefs")).load())
        val raw = Files.readAllBytes(dir.resolve("prefs.mobile"))
        assertTrue(raw[0] == 1.toByte())
        assertThrows(DocumentCorruptedException::class.java) {
            runBlocking { MobileOnlyStore(dir, "prefs", Prefs.serializer(), Prefs(), cipher("prefs", newKey())).load() }
        }
        Unit
    }
}
