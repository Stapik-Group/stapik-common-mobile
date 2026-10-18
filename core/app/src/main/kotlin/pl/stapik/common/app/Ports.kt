package pl.stapik.common.app

import pl.stapik.common.document.BackupArchive
import pl.stapik.common.document.BackupContents
import pl.stapik.common.document.MobileOnlyStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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

/** Turns [BackupContents] into bytes and back; the real one is the ZIP of [BackupArchive]. */
interface ArchiveFormat {
    fun write(contents: BackupContents): ByteArray

    /** Throws [pl.stapik.common.document.BackupFormatException] for anything that is not a valid archive. */
    fun read(bytes: ByteArray): BackupContents
}

object ZipArchiveFormat : ArchiveFormat {
    override fun write(contents: BackupContents): ByteArray =
        ByteArrayOutputStream().also { BackupArchive.write(it, contents) }.toByteArray()

    override fun read(bytes: ByteArray): BackupContents = BackupArchive.read(ByteArrayInputStream(bytes))
}
