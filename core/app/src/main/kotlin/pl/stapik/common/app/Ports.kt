package pl.stapik.common.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import pl.stapik.common.cloud.BackupSealing
import pl.stapik.common.document.BackupArchive
import pl.stapik.common.document.BackupContents
import pl.stapik.common.document.MobileOnlyStore

/** What a backup needs from one device-only store (see [MobileOnlyStore.exportJson]). */
interface MobileOnlyPort {
    suspend fun exportJson(): String

    /** Throws [IllegalArgumentException] for invalid [text]; nothing is written then. */
    suspend fun importJson(text: String)
}

fun MobileOnlyStore<*>.asPort(): MobileOnlyPort = object : MobileOnlyPort {
    override suspend fun exportJson(): String = this@asPort.exportJson()
    override suspend fun importJson(text: String) = this@asPort.importJson(text)
}

/**
 * Turns [BackupContents] into the bytes of an export file and back.
 *
 * With a passphrase the file is protected (see [pl.stapik.common.cloud.BackupSealing]); reading
 * accepts both protected and plain files.
 */
interface ArchiveFormat {
    fun write(contents: BackupContents, passphrase: String? = null): ByteArray

    /**
     * Throws [pl.stapik.common.document.BackupFormatException] for anything that is not a valid
     * archive and [pl.stapik.common.cloud.SealedBackupException] for a protected file that cannot
     * be opened with [passphrase].
     */
    fun read(bytes: ByteArray, passphrase: String? = null): BackupContents
}

/** The ZIP of [BackupArchive], optionally protected with a passphrase. */
object ZipArchiveFormat : ArchiveFormat {
    override fun write(contents: BackupContents, passphrase: String?): ByteArray {
        val zip = ByteArrayOutputStream().also { BackupArchive.write(it, contents) }.toByteArray()
        return if (passphrase.isNullOrEmpty()) zip else BackupSealing.seal(zip, passphrase)
    }

    override fun read(bytes: ByteArray, passphrase: String?): BackupContents {
        val zip = if (BackupSealing.isSealed(bytes)) BackupSealing.open(bytes, passphrase) else bytes
        return BackupArchive.read(ByteArrayInputStream(zip))
    }
}
