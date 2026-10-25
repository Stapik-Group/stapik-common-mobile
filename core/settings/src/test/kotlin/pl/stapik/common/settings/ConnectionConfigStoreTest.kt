package pl.stapik.common.settings

import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.stapik.common.cloud.CloudConfig
import pl.stapik.common.document.AesGcmCipher

class ConnectionConfigStoreTest {
    private lateinit var dir: Path
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val config = CloudConfig("https://cloud.example/", "secret-key", "phrase")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("config-test")
    }

    private fun store(
        schema: Int = 1,
        legacy: LegacyConfigReader? = null,
        aesKey: SecretKey = key,
        migration: ConfigMigration = ConfigMigration.KeepAsIs,
    ) = ConnectionConfigStore(dir, AesGcmCipher("connection".toByteArray()) { aesKey }, schema, legacy, migration = migration)

    @Test
    fun `nothing saved`() {
        assertEquals(ConfigLoad.None, store().load())
    }

    @Test
    fun `saved config is loaded back`() {
        store().save(config)
        val loaded = store().load() as ConfigLoad.Loaded
        assertEquals("https://cloud.example", loaded.config.apiUrl)
        assertEquals("secret-key", loaded.config.apiKey)
        assertEquals("phrase", loaded.config.passphrase)
    }

    @Test
    fun `config without a passphrase`() {
        store().save(CloudConfig("https://a.b", "k"))
        assertEquals(null, (store().load() as ConfigLoad.Loaded).config.passphrase)
    }

    @Test
    fun `file on disk does not contain the key`() {
        store().save(config)
        val bytes = Files.list(dir).use { files -> files.map { Files.readAllBytes(it) }.toList() }
        assertTrue(bytes.none { String(it, Charsets.ISO_8859_1).contains("secret-key") })
    }

    @Test
    fun `older schema keeps the key and the passphrase`() {
        store(schema = 1).save(config)
        val loaded = store(schema = 2).load() as ConfigLoad.Loaded
        assertEquals("secret-key", loaded.config.apiKey)
        assertEquals("phrase", loaded.config.passphrase)
        assertEquals(ConfigLoad.NewerSchema(2), store(schema = 1).load())
    }

    @Test
    fun `older schema is migrated once and saved under the current one`() {
        store(schema = 1).save(config)
        val stored = mutableListOf<Int>()
        val migration = ConfigMigration { schema, old -> stored += schema; old }
        store(schema = 2, migration = migration).load()
        store(schema = 2, migration = migration).load()
        assertEquals(listOf(1), stored)
    }

    @Test
    fun `a migration can change the config`() {
        store(schema = 1).save(config)
        val migration = ConfigMigration { _, old -> CloudConfig(old.apiUrl + "/v2", old.apiKey, old.passphrase) }
        val loaded = store(schema = 2, migration = migration).load() as ConfigLoad.Loaded
        assertEquals("https://cloud.example/v2", loaded.config.apiUrl)
        assertEquals("https://cloud.example/v2", (store(schema = 2).load() as ConfigLoad.Loaded).config.apiUrl)
    }

    @Test
    fun `a migration can reject the config which is then deleted`() {
        store(schema = 1).save(config)
        val rejecting = ConfigMigration { _, _ -> null }
        assertEquals(ConfigLoad.SchemaReset(1), store(schema = 2, migration = rejecting).load())
        assertEquals(ConfigLoad.None, store(schema = 2).load())
    }

    @Test
    fun `newer schema is reported and kept`() {
        store(schema = 3).save(config)
        assertEquals(ConfigLoad.NewerSchema(3), store(schema = 2).load())
        assertTrue(store(schema = 3).load() is ConfigLoad.Loaded)
    }

    @Test
    fun `lost key makes the config unreadable and is not overwritten by load`() {
        store().save(config)
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertEquals(ConfigLoad.Unreadable, store(aesKey = other).load())
        assertTrue(store().load() is ConfigLoad.Loaded)
    }

    @Test
    fun `clear forgets the connection`() {
        val s = store()
        s.save(config)
        s.save(config)
        s.clear()
        assertEquals(ConfigLoad.None, s.load())
        assertEquals(0L, Files.list(dir).use { it.count() })
    }

    private class FakeLegacy(var value: CloudConfig?) : LegacyConfigReader {
        var discarded = false
        override fun read() = value
        override fun discard() { discarded = true }
    }

    @Test
    fun `legacy config is migrated once`() {
        val legacy = FakeLegacy(config)
        val loaded = store(legacy = legacy).load() as ConfigLoad.Loaded
        assertTrue(loaded.migratedFromLegacy)
        assertTrue(legacy.discarded)
        val again = store(legacy = FakeLegacy(null)).load() as ConfigLoad.Loaded
        assertFalse(again.migratedFromLegacy)
        assertEquals("secret-key", again.config.apiKey)
    }

    @Test
    fun `legacy is ignored when the new config exists`() {
        store().save(config)
        val legacy = FakeLegacy(CloudConfig("https://old", "old-key"))
        assertEquals("secret-key", (store(legacy = legacy).load() as ConfigLoad.Loaded).config.apiKey)
        assertFalse(legacy.discarded)
    }

    @Test
    fun `incomplete legacy config is not migrated`() {
        assertEquals(ConfigLoad.None, store(legacy = FakeLegacy(CloudConfig("", "k"))).load())
    }
}
