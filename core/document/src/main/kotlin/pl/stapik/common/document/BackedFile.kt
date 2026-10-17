package pl.stapik.common.document

import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** The file and every backup are unreadable; nothing is returned and nothing is overwritten. */
class DocumentCorruptedException(val files: List<Path>, cause: Throwable? = null) :
    Exception("Stored data is unreadable: ${files.joinToString { it.fileName.toString() }}", cause)

/**
 * One logical file with atomic writes, rotating backups and recovery; shared by every store that
 * keeps its data in a single file.
 *
 * - **Atomic writes:** new content goes to a temporary file, is flushed, then renamed over the
 *   current file. A crash leaves the old or the new version, never half of one.
 * - **Backups:** before overwriting, the current file is rotated into `.bak`, `.bak.2`, ... up to
 *   [backupCount], but only if it is itself readable, so a corrupted file never pushes a good
 *   backup out.
 * - **Recovery:** [read] returns the newest readable version and says whether it was a backup.
 *   If files exist but none is readable it throws [DocumentCorruptedException]; it never quietly
 *   returns "nothing".
 *
 * "Readable" means: decrypts with the [cipher] and is accepted by the caller's `parse` function.
 * Blocking I/O; the caller chooses the dispatcher. Not thread-safe.
 */
class BackedFile(
    private val directory: Path,
    name: String,
    private val cipher: PayloadCipher,
    private val backupCount: Int,
) {
    init {
        require(backupCount >= 1) { "backupCount must be at least 1" }
    }

    private val current: Path = directory.resolve(name)
    private val temp: Path = directory.resolve("$name.tmp")
    private val backupBase: String = "$name.bak"

    class Loaded<R>(val value: R, val fromBackup: Boolean)

    private fun backup(index: Int): Path =
        if (index == 1) directory.resolve(backupBase) else directory.resolve("$backupBase.$index")

    /** Null when nothing was ever stored. */
    fun <R> read(parse: (ByteArray) -> R): Loaded<R>? {
        val candidates = (listOf(current) + (1..backupCount).map { backup(it) }).filter { Files.exists(it) }
        if (candidates.isEmpty()) return null
        var lastError: Throwable? = null
        for (path in candidates) {
            try {
                return Loaded(parse(cipher.decrypt(Files.readAllBytes(path))), fromBackup = path != current)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw DocumentCorruptedException(candidates, lastError)
    }

    fun <R> write(plain: ByteArray, parse: (ByteArray) -> R) {
        Files.createDirectories(directory)
        val encrypted = cipher.encrypt(plain)
        rotateBackups(parse)
        writeAndFlush(temp, encrypted)
        Files.move(temp, current, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Removes the file, its temporary file and every backup. */
    fun delete() {
        Files.deleteIfExists(current)
        Files.deleteIfExists(temp)
        for (index in 1..backupCount) Files.deleteIfExists(backup(index))
    }

    private fun <R> rotateBackups(parse: (ByteArray) -> R) {
        if (!Files.exists(current)) return
        val readable = try {
            parse(cipher.decrypt(Files.readAllBytes(current)))
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
}

/** Writes [bytes] to [path] and forces them to disk. */
internal fun writeAndFlush(path: Path, bytes: ByteArray) {
    FileOutputStream(path.toFile()).use { out ->
        out.write(bytes)
        out.flush()
        out.fd.sync()
    }
}
