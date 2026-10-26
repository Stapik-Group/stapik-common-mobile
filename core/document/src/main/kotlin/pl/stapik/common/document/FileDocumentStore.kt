package pl.stapik.common.document

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
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
 * **Conflict copies** are kept in `conflicts/`, one file each, encrypted like the document. After
 * each new copy the older ones of this slot are pruned: only the newest [maxConflictCopies] stay,
 * and of those, any older than [conflictCopyMaxAge] go too, except the newest [PROTECTED_COPIES]
 * which are always kept.
 *
 * Not thread-safe by itself: the sync engine calls it under its own mutex.
 */
class FileDocumentStore(
    directory: Path,
    private val slot: SlotKey,
    private val cipher: PayloadCipher = PayloadCipher.None,
    backupCount: Int = 3,
    private val clock: Clock = Clock.systemUTC(),
    private val maxConflictCopies: Int = DEFAULT_MAX_CONFLICT_COPIES,
    private val conflictCopyMaxAge: Duration = DEFAULT_CONFLICT_COPY_MAX_AGE,
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
        pruneConflictCopies()
    }

    override suspend fun discard() = withContext(Dispatchers.IO) { file.delete() }

    /** Conflict copies of this slot, newest first. Unreadable ones are skipped. */
    suspend fun listConflictCopies(): List<ConflictCopy> = withContext(Dispatchers.IO) {
        conflictFiles().mapNotNull { readConflictCopy(it) }
    }

    private class ConflictFile(val path: Path, val source: ConflictSource, val createdAt: Instant)

    private fun conflictFiles(): List<ConflictFile> {
        if (!Files.isDirectory(conflictsDir)) return emptyList()
        return Files.newDirectoryStream(conflictsDir).use { stream -> stream.toList() }
            .mapNotNull { path -> parseConflictFile(path) }
            .sortedByDescending { it.createdAt }
    }

    private fun parseConflictFile(path: Path): ConflictFile? {
        val fileName = path.fileName.toString()
        if (fileName.endsWith(".tmp")) return null
        val parts = fileName.removeSuffix(".json").split('.')
        if (parts.size < 3) return null
        val source = runCatching { ConflictSource.valueOf(parts.last()) }.getOrNull() ?: return null
        val millis = parts[parts.size - 2].toLongOrNull() ?: return null
        val slotName = parts.dropLast(2).joinToString(".")
        if (slotName != slot.value) return null
        return ConflictFile(path, source, Instant.ofEpochMilli(millis))
    }

    private fun readConflictCopy(file: ConflictFile): ConflictCopy? =
        try {
            val content = cipher.decrypt(Files.readAllBytes(file.path)).toString(Charsets.UTF_8)
            ConflictCopy(slot, file.source, file.createdAt, content)
        } catch (e: Exception) {
            null
        }

    private fun pruneConflictCopies() {
        val oldestAllowed = clock.instant().minus(conflictCopyMaxAge)
        conflictFiles().forEachIndexed { index, file ->
            val surplus = index >= maxConflictCopies
            val stale = index >= PROTECTED_COPIES && file.createdAt.isBefore(oldestAllowed)
            if (surplus || stale) {
                try {
                    Files.deleteIfExists(file.path)
                } catch (_: IOException) {
                }
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_CONFLICT_COPIES = 20
        const val PROTECTED_COPIES = 3
        val DEFAULT_CONFLICT_COPY_MAX_AGE: Duration = Duration.ofDays(90)
    }
}
