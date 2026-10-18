package pl.stapik.common.cloud

import java.util.Base64
import pl.stapik.common.cloud.layers.E2eBlob
import pl.stapik.common.cloud.layers.JsonProbe
import pl.stapik.common.sync.CloudException
import pl.stapik.common.sync.CloudError
import pl.stapik.common.sync.UnreadableKind

/** A passphrase-protected backup cannot be opened; [kind] says what to ask the user for. */
class SealedBackupException(val kind: UnreadableKind, message: String) : Exception(message)

/**
 * Passphrase protection of an exported backup file, with the same primitives as `stapik-e2e` in the
 * cloud (Argon2id + XChaCha20-Poly1305).
 *
 * The protected file is a `stapik-e2e` JSON blob whose plaintext is the Base64 of the archive. The
 * blob is bound to this purpose (associated data `stapik-e2e/1/backup/archive`), so a cloud
 * document cannot be passed off as a backup or the reverse. This container is specific to the
 * mobile apps; the desktop does not read it.
 */
object BackupSealing {
    private const val SCOPE = "backup"
    private const val CONTEXT = "archive"

    /** Whether [bytes] is a protected file (as opposed to a plain archive). */
    fun isSealed(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes[0] != '{'.code.toByte()) return false
        return E2eBlob.parse(bytes.toString(Charsets.UTF_8)) != null
    }

    fun seal(archive: ByteArray, passphrase: String): ByteArray {
        require(passphrase.isNotEmpty()) { "Passphrase must not be empty" }
        val text = Base64.getEncoder().encodeToString(archive)
        return E2eBlob(passphrase, SCOPE).seal(text, CONTEXT).toByteArray(Charsets.UTF_8)
    }

    /** Throws [SealedBackupException] when the passphrase is missing or wrong or the file is damaged. */
    fun open(sealed: ByteArray, passphrase: String?): ByteArray {
        val blob = E2eBlob.parse(sealed.toString(Charsets.UTF_8))
            ?: throw SealedBackupException(UnreadableKind.OTHER, "This is not a protected backup")
        if (passphrase.isNullOrEmpty()) {
            throw SealedBackupException(UnreadableKind.PASSPHRASE_REQUIRED, "This backup is protected with a passphrase")
        }
        val text = try {
            E2eBlob(passphrase, SCOPE).open(blob, CONTEXT)
        } catch (e: CloudException) {
            val unreadable = e.error as? CloudError.Unreadable
            throw SealedBackupException(unreadable?.kind ?: UnreadableKind.OTHER, unreadable?.reason ?: "Cannot open the backup")
        }
        return try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            throw SealedBackupException(UnreadableKind.OTHER, "The protected backup is damaged")
        }
    }
}
