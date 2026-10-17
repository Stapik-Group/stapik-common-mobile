package pl.stapik.common.document

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MobileOnlyStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Serializable
    data class Prefs(val n: Int = 0, val tags: List<String> = emptyList())

    private val dir: Path get() = tmp.root.toPath()

    private object MarkedCipher : PayloadCipher {
        override fun encrypt(plain: ByteArray) = byteArrayOf(0x45) + ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }

        override fun decrypt(encrypted: ByteArray): ByteArray {
            require(encrypted.isNotEmpty() && encrypted[0] == 0x45.toByte()) { "wrong key" }
            return ByteArray(encrypted.size - 1) { (encrypted[it + 1].toInt() xor 0x5A).toByte() }
        }
    }

    private fun store(cipher: PayloadCipher = PayloadCipher.None, version: Int = 1) =
        MobileOnlyStore(dir, "prefs", Prefs.serializer(), Prefs(), cipher, schemaVersion = version)

    @Test
    fun `nothing stored gives the default`() = runBlocking {
        assertEquals(Prefs(), store().load())
    }

    @Test
    fun `a saved value comes back, also from a new instance`() = runBlocking {
        store().save(Prefs(3, listOf("a", "b")))
        assertEquals(Prefs(3, listOf("a", "b")), store().load())
    }

    @Test
    fun `update stores and returns the transformed value`() = runBlocking {
        val s = store()
        assertEquals(Prefs(1), s.update { it.copy(n = it.n + 1) })
        assertEquals(Prefs(2), s.update { it.copy(n = it.n + 1) })
        assertEquals(Prefs(2), store().load())
    }

    @Test
    fun `concurrent updates are not lost`() = runBlocking {
        val s = store()
        (1..100).map { async(Dispatchers.Default) { s.update { p -> p.copy(n = p.n + 1) } } }.awaitAll()
        assertEquals(100, s.load().n)
    }

    @Test
    fun `unknown fields in the stored data are ignored`() = runBlocking {
        Files.write(dir.resolve("prefs.mobile"), """{"version":1,"data":{"n":7,"future":true}}""".toByteArray())
        assertEquals(Prefs(7), store().load())
    }

    @Test
    fun `a corrupted file is recovered from the backup`() = runBlocking {
        val s = store()
        s.save(Prefs(1))
        s.save(Prefs(2))
        Files.write(dir.resolve("prefs.mobile"), "garbage".toByteArray())
        assertEquals(Prefs(1), s.load())
        assertTrue(s.lastLoadRecoveredFromBackup)
    }

    @Test
    fun `when nothing is readable it throws instead of returning the default`() = runBlocking {
        val s = store()
        s.save(Prefs(1))
        Files.write(dir.resolve("prefs.mobile"), "garbage".toByteArray())
        assertThrows(DocumentCorruptedException::class.java) { runBlocking { s.load() } }
        Unit
    }

    @Test
    fun `a different schema version is not read as valid`() = runBlocking {
        store(version = 1).save(Prefs(5))
        assertThrows(DocumentCorruptedException::class.java) { runBlocking { store(version = 2).load() } }
        Unit
    }

    @Test
    fun `data on disk is encrypted`() = runBlocking {
        store(MarkedCipher).save(Prefs(1, listOf("secret-tag")))
        val raw = String(Files.readAllBytes(dir.resolve("prefs.mobile")), Charsets.ISO_8859_1)
        assertFalse(raw.contains("secret-tag"))
        assertEquals(Prefs(1, listOf("secret-tag")), store(MarkedCipher).load())
    }
}
