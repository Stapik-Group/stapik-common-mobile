package pl.stapik.common.document

import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int

/**
 * Data that lives only on this device and is never sent to the cloud or put into the synced
 * document: for example today's check-offs, notification preferences, theme choice.
 *
 * Holds one value of type [T], stored as `{"version": n, "data": {...}}` in a [BackedFile] (atomic
 * writes, rotating backups, recovery, optional encryption). [update] is a serialised
 * read-modify-write, so concurrent updates are not lost.
 *
 * Retention (for example "keep only the last 30 days") is the caller's business: do it inside
 * the `transform` passed to [update].
 *
 * @param name file name stem; one store per name in [directory].
 * @param default what [load] returns when nothing has been stored yet.
 * @param schemaVersion a stored file with a different version is reported as corrupted for now;
 *   migrations are added when the first schema change needs one.
 */
class MobileOnlyStore<T>(
    directory: Path,
    name: String,
    private val serializer: KSerializer<T>,
    private val default: T,
    cipher: PayloadCipher = PayloadCipher.None,
    backupCount: Int = 2,
    private val schemaVersion: Int = 1,
) {
    private val file = BackedFile(directory, "$name.mobile", cipher, backupCount)
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** True if the last [load] or [update] had to use a backup. */
    @Volatile
    var lastLoadRecoveredFromBackup: Boolean = false
        private set

    suspend fun load(): T = mutex.withLock { withContext(Dispatchers.IO) { readValue() } }

    suspend fun save(value: T) = mutex.withLock { withContext(Dispatchers.IO) { writeValue(value) } }

    /** Applies [transform] to the stored value (or the default), stores the result and returns it. */
    suspend fun update(transform: (T) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            transform(readValue()).also { writeValue(it) }
        }
    }

    private fun readValue(): T {
        val loaded = file.read { decode(it) }
        lastLoadRecoveredFromBackup = loaded?.fromBackup ?: false
        return loaded?.value ?: default
    }

    private fun writeValue(value: T) {
        val root = JsonObject(
            mapOf(
                VERSION to JsonPrimitive(schemaVersion),
                DATA to json.encodeToJsonElement(serializer, value),
            ),
        )
        file.write(root.toString().toByteArray(Charsets.UTF_8)) { decode(it) }
    }

    private fun decode(bytes: ByteArray): T {
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            ?: throw IllegalArgumentException("Stored data is not a JSON object")
        val version = (root[VERSION] as? JsonPrimitive)?.int
        require(version == schemaVersion) { "Unsupported stored version: $version" }
        val data = root[DATA] ?: throw IllegalArgumentException("Stored data has no 'data'")
        return json.decodeFromJsonElement(serializer, data)
    }

    private companion object {
        const val VERSION = "version"
        const val DATA = "data"
    }
}
