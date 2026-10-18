package pl.stapik.common.document

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SafetyCopiesTest {
    private lateinit var dir: Path
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val fixed = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("safety-test")
    }

    private fun copies(keep: Int = 5, clock: Clock = fixed) = SafetyCopies(dir, AesGcmCipher { key }, keep, clock)

    @Test
    fun `saved copy is listed and read back`() = runBlocking {
        val c = copies()
        val saved = c.save("zip-bytes".toByteArray())
        assertEquals(listOf(saved), c.list())
        assertArrayEquals("zip-bytes".toByteArray(), c.read(saved.name))
    }

    @Test
    fun `two copies in the same millisecond do not overwrite each other`() = runBlocking {
        val c = copies()
        val a = c.save("a".toByteArray())
        val b = c.save("b".toByteArray())
        assertTrue(a.name != b.name)
        assertArrayEquals("a".toByteArray(), c.read(a.name))
        assertEquals(listOf(b, a), c.list())
    }

    @Test
    fun `only the newest are kept`() = runBlocking {
        var now = 1_000L
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant(): Instant = Instant.ofEpochMilli(now)
        }
        val c = copies(keep = 2, clock = clock)
        val names = (1..4).map { now = it * 1_000L; c.save("v$it".toByteArray()).name }
        assertEquals(names.takeLast(2).reversed(), c.list().map { it.name })
    }

    @Test
    fun `contents are encrypted on disk`() = runBlocking {
        copies().save("very-secret-budget".toByteArray())
        val raw = Files.list(dir).use { files -> files.map { String(Files.readAllBytes(it), Charsets.ISO_8859_1) }.toList() }
        assertTrue(raw.none { it.contains("very-secret-budget") })
    }

    @Test
    fun `names that are not copies are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { copies().read("../etc/passwd") } }
    }

    @Test
    fun `empty directory lists nothing`() = runBlocking {
        assertEquals(emptyList<SafetyCopy>(), copies().list())
    }
}
