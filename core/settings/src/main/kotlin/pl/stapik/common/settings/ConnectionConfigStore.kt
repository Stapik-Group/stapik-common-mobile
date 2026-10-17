package pl.stapik.common.settings

import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import pl.stapik.common.cloud.CloudConfig
import pl.stapik.common.document.BackedFile
import pl.stapik.common.document.DocumentCorruptedException
import pl.stapik.common.document.PayloadCipher

/** What [ConnectionConfigStore.load] found. */
sealed interface ConfigLoad {
    /** Nothing saved: the user has not connected yet. */
    data object None : ConfigLoad

    data class Loaded(val config: CloudConfig, val migratedFromLegacy: Boolean = false) : ConfigLoad

    /**
     * The saved configuration came from an older schema and was deleted ([ApiSchemaGuard]); the
     * user has to connect again.
     */
    data class SchemaReset(val storedVersion: Int) : ConfigLoad

    /** The saved configuration was written by a newer version of the app. It is left untouched. */
    data class NewerSchema(val storedVersion: Int) : ConfigLoad

    /** The file and its backups cannot be read (for example the Keystore key is gone). */
    data object Unreadable : ConfigLoad
}

/**
 * Reads the connection settings an app kept before this library existed, so that users do not
 * have to connect again. One implementation per app, because aliases and formats differ.
 */
interface LegacyConfigReader {
    /** The old configuration, or null if there is none or it cannot be read. */
    fun read(): CloudConfig?

    /** Removes the old storage; called only after the configuration was saved in the new format. */
    fun discard()
}

/**
 * Connection settings (API URL, key, optional end-to-end passphrase) in one encrypted file with
 * backups.
 *
 * [schemaVersion] is the app's [ApiSchemaGuard]: when the stored version is lower the
 * configuration is deleted and the user connects again. This is the emergency lever for changes
 * that make old configurations invalid. A higher stored version is never deleted.
 *
 * Blocking I/O; the caller chooses the dispatcher. Not thread-safe.
 */
class ConnectionConfigStore(
    directory: Path,
    cipher: PayloadCipher,
    private val schemaVersion: Int,
    private val legacy: LegacyConfigReader? = null,
    name: String = "connection.json",
    backupCount: Int = 2,
) {
    private val file = BackedFile(directory, name, cipher, backupCount)

    private class Stored(val schema: Int, val config: CloudConfig)

    fun load(): ConfigLoad {
        val stored = try {
            file.read(::parse)?.value
        } catch (e: DocumentCorruptedException) {
            return ConfigLoad.Unreadable
        }
        if (stored == null) return migrateLegacy()
        return when {
            stored.schema < schemaVersion -> {
                file.delete()
                ConfigLoad.SchemaReset(stored.schema)
            }
            stored.schema > schemaVersion -> ConfigLoad.NewerSchema(stored.schema)
            else -> ConfigLoad.Loaded(stored.config)
        }
    }

    private fun migrateLegacy(): ConfigLoad {
        val reader = legacy ?: return ConfigLoad.None
        val old = reader.read()?.takeIf { it.isConfigured } ?: return ConfigLoad.None
        save(old)
        reader.discard()
        return ConfigLoad.Loaded(old, migratedFromLegacy = true)
    }

    fun save(config: CloudConfig) {
        val json = JsonObject(
            buildMap {
                put("schema", JsonPrimitive(schemaVersion))
                put("apiUrl", JsonPrimitive(config.normalizedApiUrl))
                put("apiKey", JsonPrimitive(config.apiKey))
                config.passphrase?.takeIf { it.isNotEmpty() }?.let { put("passphrase", JsonPrimitive(it)) }
            },
        )
        file.write(json.toString().toByteArray(Charsets.UTF_8), ::parse)
    }

    /** Forgets the connection (disconnect). */
    fun clear() = file.delete()

    private fun parse(bytes: ByteArray): Stored {
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
        fun string(name: String) = (root[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val schema = (root["schema"] as? JsonPrimitive)?.content?.toIntOrNull() ?: error("missing schema")
        return Stored(schema, CloudConfig(string("apiUrl").orEmpty(), string("apiKey").orEmpty(), string("passphrase")))
    }
}
