package pl.stapik.common.document

import java.io.FileOutputStream
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

/** The document file and every backup are unreadable; nothing is returned and nothing is overwritten. */
class DocumentCorruptedException(val files: List<Path>, cause: Throwable? = null) :
    Exception("Stored document is unreadable: ${files.joinToString { it.fileName.toString() }}", cause)

/**
 * A [DocumentStore] on plain files in [directory].
 *
 * - **Atomic writes:** the new content goes to a temporary file, is flushed, then renamed over the
 *   current file, so a crash leaves either the old or the new version, never half of one.
 * - **Backups:** before overwriting, the current file is rotated into `.bak`, `.bak.2`, ... up to
 *   [backupCount] — but only if it is itself readable, so a corrupted file never pushes a good
 *   backup out.
 * - **Recovery:** if the current file is unreadable, the newest readable backup is returned
 *   ([lastLoadRecoveredFromBackup] tells the caller). If nothing is readable it throws
 *   [DocumentCorruptedException]; it never quietly returns an empty document.
 * - **Conflict copies** are kept in `conflicts/`, one file each, encrypted like the document.
 *
 * Not thread-safe by itself: the sync engine calls it under its own mutex.
 */
class FileDocumentStore(
    private val directory: Path,
    private val slot: SlotKey,
    private val cipher: PayloadCipher = PayloadCipher.None,
    private val backupCount: Int = 3,
    private val clock: Clock = Clock.systemUTC(),
) : DocumentStore {

    init {
        require(backupCount >= 1) { "backupCount must be at least 1" }
    }

    /** True if the last [load] could not use the current file and returned a backup instead. */
    @Volatile
    var lastLoadRecoveredFromBackup: Boolean = false
        private set

    private val current: Path = directory.resolve("${slot.value}.doc")
    private val conflictsDir: Path = directory.resolve("conflicts")

    private fun backup(index: Int): Path =
        if (index == 1) directory.resolve("${slot.value}.doc.bak") else directory.resolve("${slot.value}.doc.bak.$index")

    override suspend fun load(): LocalDocument<DocumentEnvelope>? = withContext(Dispatchers.IO) {
        val candidates = (listOf(current) + (1..backupCount).map { backup(it) }).filter { Files.exists(it) }
        if (candidates.isEmpty()) {
            lastLoadRecoveredFromBackup = false
            return@withContext null
        }
        var lastError: Throwable? = null
        for (path in candidates) {
            try {
                val document = read(path)
                lastLoadRecoveredFromBackup = path != current
                return@withContext document
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw DocumentCorruptedException(candidates, lastError)
    }

    override suspend fun save(document: LocalDocument<DocumentEnvelope>) = withContext(Dispatchers.IO) {
        Files.createDirectories(directory)
        val encrypted = cipher.encrypt(StoredDocument.encode(document))
        rotateBackups()
        val temp = directory.resolve("${slot.value}.doc.tmp")
        writeAndFlush(temp, encrypted)
        Files.move(temp, current, StandardCopyOption.ATOMIC_MOVE)
        Unit
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
        val parts = path.fileName.toString().removeSuffix(".json").split('.')
        // "<slot>.<millis>.<SOURCE>" where the slot itself contains a dot, so read from the end.
        if (parts.size < 3 || path.fileName.toString().endsWith(".tmp")) return null
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

    private fun read(path: Path): LocalDocument<DocumentEnvelope> =
        StoredDocument.decode(cipher.decrypt(Files.readAllBytes(path)))

    /** Shifts `.bak` files up by one and copies the current file to `.bak`, if the current file is readable. */
    private fun rotateBackups() {
        if (!Files.exists(current)) return
        val readable = try {
            read(current)
            true
        } catch (e: Exception) {
            false
        }
        if (!readable) return
        for (index in backupCount downTo 2) {
            val from = backup(index - 1)
            if (Files.exists(from)) Files.move(from, backup(index), StandardCopyOption.REPLACE_EXISTING)
        }
        Files.copy(current, backup(1), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun writeAndFlush(path: Path, bytes: ByteArray) {
        FileOutputStream(path.toFile()).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }
}
