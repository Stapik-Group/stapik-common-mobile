package pl.stapik.common.app

/** Produces the bytes of an export file for the user to save. */
class ExportService(
    private val backup: AppBackup,
    private val archive: ArchiveFormat,
) {
    /** A protected file when [passphrase] is not empty, otherwise a plain archive (the UI should say so). */
    suspend fun export(passphrase: String? = null): ByteArray = archive.write(backup.snapshot(), passphrase)
}
