package pl.stapik.common.document

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.stapik.common.model.DocumentEnvelope
import pl.stapik.common.model.LocalDocument
import pl.stapik.common.model.SlotKey
import pl.stapik.common.sync.ConflictCopy
import pl.stapik.common.sync.ConflictSource
import pl.stapik.common.sync.DocumentStore

/**
 * A [DocumentStore] on plain files in [directory].
 *
 * The document itself lives in a [BackedFile] (atomic writes, rotating backups, recovery; see
 * there). [lastLoadRecoveredFromBackup] tells the caller that the last [load] had to use a backup.
 *
 * **Conflict copies** are kept in `conflicts/`, one file each, encrypted like the document.
 *
 * Not thread-safe by itself: the sync engine calls it under its own mutex.
 */
class FileDocumentStore(
    directory: Path,
    private val slot: SlotKey,
    private val cipher: PayloadCipher = PayloadCipher.None,
    backupCount: Int = 3,
    @Suppress("unused") private val clock: Clock = Clock.systemUTC(),
) : DocumentStore {

    private val file = BackedFile(directory, "${slot.value}.doc", cipher, backupCount)
    private val conflictsDir: Path = directory.resolve("conflicts")

    /** True if the last [load] could not use the current file and returned a backup instead. */
    @Volatile
    var lastLoadRecoveredFromBackup: Boolean = false
        private set

    override suspend fun load(): LocalDocument<DocumentEnvelope>? = withContext(Dispatchers.IO) {
        val loaded = file.read { StoredDocument.decode(it) }
        lastLoadRecoveredFromBackup = loaded?.fromBackup ?: false
        loaded?.value
    }

    override suspend fun save(document: LocalDocument<DocumentEnvelope>) = withContext(Dispatchers.IO) {
        file.write(StoredDocument.encode(document)) { StoredDocument.decode(it) }
    }

    override suspend fun saveConflictCopy(copy: ConflictCopy) = withContext(Dispatchers.IO) {
        Files.createDirectories(conflictsDir)
        val name = "${copy.slot.value}.${copy.createdAt.toEpochMilli()}.${copy.source.name}.json"
        val temp = conflictsDir.resolve("$name.tmp")
        writeAndFlush(temp, cipher.encrypt(copy.content.toByteArray(Charsets.UTF_8)))
        Files.move(temp, conflictsDir.resolve(name), StandardCopyOption.ATOMIC_MOVE)
        Unit
    }

    /** Conflict copies of this slot, newest first. Unreadable ones are skipped. */
    suspend fun listConflictCopies(): List<ConflictCopy> = withContext(Dispatchers.IO) {
        if (!Files.isDirectory(conflictsDir)) return@withContext emptyList()
        Files.newDirectoryStream(conflictsDir).use { stream -> stream.toList() }
            .mapNotNull { path -> parseConflictCopy(path) }
            .sortedByDescending { it.createdAt }
    }

    private fun parseConflictCopy(path: Path): ConflictCopy? {
        val fileName = path.fileName.toString()
        if (fileName.endsWith(".tmp")) return null
        // "<slot>.<millis>.<SOURCE>.json"; the slot itself contains a dot, so read from the end.
        val parts = fileName.removeSuffix(".json").split('.')
        if (parts.size < 3) return null
        val source = runCatching { ConflictSource.valueOf(parts.last()) }.getOrNull() ?: return null
        val millis = parts[parts.size - 2].toLongOrNull() ?: return null
        val slotName = parts.dropLast(2).joinToString(".")
        if (slotName != slot.value) return null
        return try {
            val content = cipher.decrypt(Files.readAllBytes(path)).toString(Charsets.UTF_8)
            ConflictCopy(slot, source, Instant.ofEpochMilli(millis), content)
        } catch (e: Exception) {
            null
        }
    }
}
