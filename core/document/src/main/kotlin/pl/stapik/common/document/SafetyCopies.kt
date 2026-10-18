package pl.stapik.common.document

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** @property name pass it to [SafetyCopies.read]. */
data class SafetyCopy(val name: String, val createdAt: Instant)

/**
 * Whole-app snapshots (as backup archives) taken automatically before something replaces the
 * user's data, such as an import or a restore. The newest [keep] are retained.
 *
 * Files are encrypted with [cipher] like the rest of the data at rest, so they protect against a
 * mistake or a bad file, not against losing the key.
 */
class SafetyCopies(
    private val directory: Path,
    private val cipher: PayloadCipher = PayloadCipher.None,
    private val keep: Int = 5,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(keep >= 1) { "keep must be at least 1" }
    }

    /** Stores [bytes] and returns the new copy; older copies beyond [keep] are removed. */
    suspend fun save(bytes: ByteArray): SafetyCopy = withContext(Dispatchers.IO) {
        Files.createDirectories(directory)
        var millis = clock.millis()
        while (Files.exists(path(millis))) millis++
        val temp = directory.resolve("safety-$millis.tmp")
        writeAndFlush(temp, cipher.encrypt(bytes))
        Files.move(temp, path(millis), StandardCopyOption.ATOMIC_MOVE)
        prune()
        SafetyCopy(nameOf(millis), Instant.ofEpochMilli(millis))
    }

    /** Newest first. */
    suspend fun list(): List<SafetyCopy> = withContext(Dispatchers.IO) { listBlocking() }

    /** Throws [IllegalArgumentException] for a name that is not one of [list], other I/O or decryption errors as they are. */
    suspend fun read(name: String): ByteArray = withContext(Dispatchers.IO) {
        val millis = parse(name) ?: throw IllegalArgumentException("Not a safety copy: $name")
        cipher.decrypt(Files.readAllBytes(path(millis)))
    }

    private fun listBlocking(): List<SafetyCopy> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.newDirectoryStream(directory).use { stream -> stream.toList() }
            .mapNotNull { parse(it.fileName.toString()) }
            .sortedDescending()
            .map { SafetyCopy(nameOf(it), Instant.ofEpochMilli(it)) }
    }

    private fun prune() {
        listBlocking().drop(keep).forEach { Files.deleteIfExists(path(parse(it.name)!!)) }
    }

    private fun nameOf(millis: Long) = "safety-$millis.bak"

    private fun path(millis: Long): Path = directory.resolve(nameOf(millis))

    private fun parse(name: String): Long? = NAME.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()

    private companion object {
        val NAME = Regex("safety-(\\d{1,18})\\.bak")
    }
}
