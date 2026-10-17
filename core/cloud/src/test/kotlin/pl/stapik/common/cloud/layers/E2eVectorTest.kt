package pl.stapik.common.cloud.layers

import org.junit.Test
import org.junit.Assert.assertEquals
import pl.stapik.common.cloud.assertFailsWith
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import pl.stapik.common.model.SlotKey
import pl.stapik.common.sync.CloudError
import pl.stapik.common.sync.CloudException
import pl.stapik.common.sync.UnreadableKind

/** The vectors were produced by real libsodium (crypto_pwhash interactive + xchacha20poly1305_ietf). */
class E2eVectorTest {
    private val passphrase = "correct horse battery staple żółć"
    private val slot = SlotKey("budget.json")
    private val keyHex = "7236977b09a4d855a81c274012154890f29a295bd498a1e0a9267a27e958eb25"
    private val plain = """{"lastUpdate":"2026-10-15T08:00:00Z","payload":{"items":["a","b"]}}"""
    private val blob = """{"ciphertext":"2IMhpYZTAZ4U2+vOAEajaKzqSrMuu1MofsnB+PyqueL6BazTrNT/H2JGFUu+W0CoRqMR3RvSskx6TGxQYmJBD0ZmnX/nZzEzjPUKW+VaEgBTUxY=","format":"stapik-e2e","nonce":"ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7","salt":"AAECAwQFBgcICQoLDA0ODw==","version":1}"""

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `derives the same key as libsodium`() {
        assertEquals(keyHex, hex(XChaChaBox.deriveKey(passphrase, ByteArray(16) { it.toByte() })))
    }

    @Test
    fun `opens a blob produced by libsodium`() {
        assertEquals(plain, ContentLayers(slot, passphrase).open(blob))
    }

    @Test
    fun `sealing with the same salt and nonce gives the libsodium ciphertext`() {
        val salt = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(24) { (100 + it).toByte() }
        var call = 0
        val random: (Int) -> ByteArray = { if (call++ == 0) salt else nonce }
        val sealed = E2eBlob(passphrase, slot.value, random).seal(plain, "document")
        assertEquals(JsonProbe.parseObject(blob), JsonProbe.parseObject(sealed))
    }

    @Test
    fun `blob has a top level format and no plaintext`() {
        val sealed = ContentLayers(slot, passphrase).seal(plain)
        assertNotNull(E2eBlob.parse(sealed))
        assertTrue(!sealed.contains("lastUpdate"))
    }

    @Test
    fun `round trip with random salt`() {
        val layers = ContentLayers(slot, passphrase)
        assertEquals(plain, layers.open(layers.seal(plain)))
    }

    @Test
    fun `blob of another slot does not open`() {
        val e = assertFailsWith<CloudException> { ContentLayers(SlotKey("calendar.json"), passphrase).open(blob) }
        assertEquals(UnreadableKind.DECRYPTION_FAILED, (e.error as CloudError.Unreadable).kind)
    }

    @Test
    fun `wrong passphrase`() {
        val e = assertFailsWith<CloudException> { ContentLayers(slot, "nope").open(blob) }
        assertEquals(UnreadableKind.DECRYPTION_FAILED, (e.error as CloudError.Unreadable).kind)
    }

    @Test
    fun `passphrase required`() {
        val e = assertFailsWith<CloudException> { ContentLayers(slot, null).open(blob) }
        assertEquals(UnreadableKind.PASSPHRASE_REQUIRED, (e.error as CloudError.Unreadable).kind)
    }

    @Test
    fun `newer blob version is unsupported`() {
        val newer = blob.replace("\"version\":1", "\"version\":2")
        val e = assertFailsWith<CloudException> { ContentLayers(slot, passphrase).open(newer) }
        assertEquals(UnreadableKind.UNSUPPORTED_VERSION, (e.error as CloudError.Unreadable).kind)
    }

    @Test
    fun `plain data is accepted when a passphrase is set`() {
        assertEquals(plain, ContentLayers(slot, passphrase).open(plain))
    }

    @Test
    fun `empty content is an empty document`() {
        assertEquals("{}", ContentLayers(slot, null).open(""))
    }
}
