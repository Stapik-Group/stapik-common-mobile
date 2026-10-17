package pl.stapik.common.sample

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pl.stapik.common.crypto.KeystoreCiphers
import pl.stapik.common.crypto.KeystoreKeys
import pl.stapik.common.document.BackupArchive
import pl.stapik.common.document.BackupContents
import pl.stapik.common.document.FileDocumentStore
import pl.stapik.common.document.MobileOnlyStore
import pl.stapik.common.model.DocumentCodec
import pl.stapik.common.model.SlotKey
import pl.stapik.common.sync.SyncEngine
import pl.stapik.common.sync.SyncOutcome

/**
 * Checks on a real device what the JVM tests cannot: the Android Keystore cipher together with the
 * file stores, the sync engine and the backup archive. Prints one line per check.
 */
class SelfTest(private val dir: File) {
    private val lines = mutableListOf<String>()
    private var failed = 0

    fun run(): String {
        dir.deleteRecursively()
        KeystoreKeys.delete(ALIAS)
        val cipher = KeystoreCiphers.create(ALIAS, "selftest".toByteArray())

        check("keystore cipher round-trip") {
            val plain = "hello".toByteArray()
            val encrypted = cipher.encrypt(plain)
            require(!encrypted.contentEquals(plain)) { "not encrypted" }
            require(cipher.decrypt(encrypted).contentEquals(plain)) { "round-trip mismatch" }
        }

        check("mobile-only store (encrypted)") {
            val store = MobileOnlyStore(dir.resolve("m").toPath(), "prefs", JsonObject.serializer(), JsonObject(emptyMap()), cipher)
            val value = JsonObject(mapOf("n" to JsonPrimitive(7)))
            runBlocking { store.save(value) }
            val back = runBlocking { MobileOnlyStore(dir.resolve("m").toPath(), "prefs", JsonObject.serializer(), JsonObject(emptyMap()), cipher).load() }
            require(back == value) { "got $back" }
            val raw = dir.resolve("m").resolve("prefs.mobile").readBytes()
            require(!String(raw, Charsets.ISO_8859_1).contains("\"n\"")) { "plain text on disk" }
        }

        check("sync engine with encrypted file store (no cloud)") {
            val codec = DocumentCodec(JsonObject.serializer()) { it.isEmpty() }
            val store = FileDocumentStore(dir.resolve("d").toPath(), SlotKey("selftest.json"), cipher)
            val engine = SyncEngine(SlotKey("selftest.json"), codec, store, { null }, Clock.systemUTC())
            val outcome = runBlocking { engine.edit { JsonObject(mapOf("items" to JsonPrimitive("a"))) } }
            require(outcome == SyncOutcome.LocalOnly) { "outcome $outcome" }
            val again = SyncEngine(SlotKey("selftest.json"), codec, FileDocumentStore(dir.resolve("d").toPath(), SlotKey("selftest.json"), cipher), { null }, Clock.systemUTC())
            val loaded = runBlocking { again.load() }
            require(loaded["items"] == JsonPrimitive("a")) { "got $loaded" }
        }

        check("backup archive round-trip") {
            val contents = BackupContents("sample", Instant.parse("2026-10-18T10:00:00Z"), """{"lastUpdate":"t","payload":{}}""", mapOf("prefs" to "{}"))
            val out = ByteArrayOutputStream().also { BackupArchive.write(it, contents) }
            require(BackupArchive.read(ByteArrayInputStream(out.toByteArray())) == contents) { "mismatch" }
        }

        check("wrong key is reported, not returned as data") {
            val other = KeystoreCiphers.create("pl.stapik.common.sample.other", "selftest".toByteArray())
            val encrypted = other.encrypt("x".toByteArray())
            val failedAsExpected = try {
                cipher.decrypt(encrypted)
                false
            } catch (e: Exception) {
                true
            }
            KeystoreKeys.delete("pl.stapik.common.sample.other")
            require(failedAsExpected) { "decrypted with the wrong key" }
        }

        lines += ""
        lines += if (failed == 0) "ALL PASSED" else "$failed FAILED"
        return lines.joinToString("\n")
    }

    private fun check(name: String, block: () -> Unit) {
        try {
            block()
            lines += "OK    $name"
        } catch (e: Throwable) {
            failed++
            lines += "FAIL  $name: $e"
        }
    }

    private companion object {
        const val ALIAS = "pl.stapik.common.sample.selftest"
    }
}
