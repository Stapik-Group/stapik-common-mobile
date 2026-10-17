package pl.stapik.common.document

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import pl.stapik.common.model.DocumentEnvelope
import pl.stapik.common.model.EnvelopeFormatException

/** The archive is not a valid backup (damaged, tampered with, wrong format, too large). */
class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * What a backup holds.
 *
 * @property document the synced document as envelope text, exactly as it would be sent to the
 *   cloud (so it is also a valid desktop-format file); null if the app had none.
 * @property mobileOnly device-only data by store name, each in the format of
 *   [MobileOnlyStore.exportJson].
 */
data class BackupContents(
    val appId: String,
    val createdAt: Instant,
    val document: String?,
    val mobileOnly: Map<String, String> = emptyMap(),
)

@Serializable
internal data class BackupManifest(
    val formatVersion: Int,
    val appId: String,
    val createdAt: String,
    val files: List<BackupManifestFile>,
)

@Serializable
internal data class BackupManifestFile(val path: String, val sha256: String, val size: Long)

/**
 * Manual export/import format (plan 4.6): a ZIP with `manifest.json`, `document.json` and
 * `mobile-only/<name>.json`. The manifest lists every file with its SHA-256, so a damaged or
 * edited archive is rejected instead of half-imported.
 *
 * Reading is defensive: only the known entry names are accepted (no paths, so no path traversal),
 * the number and size of entries are capped, and the document must be a valid envelope.
 *
 * Not included here: passphrase encryption of the archive (`stapik-e2e`), which needs the crypto
 * library chosen in a later phase. The archive is plain, so the app should say so before saving it.
 */
object BackupArchive {
    const val FORMAT_VERSION = 1
    const val DEFAULT_MAX_ENTRY_BYTES: Long = 32L * 1024 * 1024

    private const val MANIFEST = "manifest.json"
    private const val DOCUMENT = "document.json"
    private const val MOBILE_PREFIX = "mobile-only/"
    private const val MAX_ENTRIES = 200
    private val NAME = Regex("[A-Za-z0-9._-]{1,100}")
    private val MOBILE_PATH = Regex("mobile-only/([A-Za-z0-9._-]{1,100})\\.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Writes the archive to [out]. Does not close [out]. */
    fun write(out: OutputStream, contents: BackupContents) {
        val files = LinkedHashMap<String, ByteArray>()
        contents.document?.let { files[DOCUMENT] = it.toByteArray(Charsets.UTF_8) }
        for ((name, text) in contents.mobileOnly.toSortedMap()) {
            require(NAME.matches(name)) { "Invalid store name: '$name'" }
            files["$MOBILE_PREFIX$name.json"] = text.toByteArray(Charsets.UTF_8)
        }
        val manifest = BackupManifest(
            formatVersion = FORMAT_VERSION,
            appId = contents.appId,
            createdAt = contents.createdAt.toString(),
            files = files.map { (path, bytes) -> BackupManifestFile(path, sha256(bytes), bytes.size.toLong()) },
        )
        val zip = ZipOutputStream(out)
        zip.putEntry(MANIFEST, json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8), contents.createdAt)
        for ((path, bytes) in files) zip.putEntry(path, bytes, contents.createdAt)
        zip.finish()
        zip.flush()
    }

    /**
     * Reads and verifies an archive. Does not close [input].
     * Throws [BackupFormatException] for anything that is not a valid, untampered backup.
     */
    fun read(input: InputStream, maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES): BackupContents {
        val entries = readEntries(input, maxEntryBytes)
        val manifestBytes = entries[MANIFEST] ?: throw BackupFormatException("manifest.json is missing")
        val manifest = try {
            json.decodeFromString(BackupManifest.serializer(), manifestBytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw BackupFormatException("manifest.json is not valid", e)
        }
        if (manifest.formatVersion != FORMAT_VERSION) {
            throw BackupFormatException("Unsupported backup format version: ${manifest.formatVersion}")
        }
        val createdAt = try {
            Instant.parse(manifest.createdAt)
        } catch (e: DateTimeParseException) {
            throw BackupFormatException("Invalid creation time in manifest", e)
        }

        val listed = manifest.files.map { it.path }
        if (listed.toSet().size != listed.size) throw BackupFormatException("Manifest lists a file twice")
        val unlisted = entries.keys - MANIFEST - listed.toSet()
        if (unlisted.isNotEmpty()) throw BackupFormatException("Archive has unlisted files: $unlisted")

        var document: String? = null
        val mobileOnly = LinkedHashMap<String, String>()
        for (file in manifest.files) {
            val bytes = entries[file.path] ?: throw BackupFormatException("Listed file is missing: ${file.path}")
            if (bytes.size.toLong() != file.size || sha256(bytes) != file.sha256) {
                throw BackupFormatException("File does not match the manifest: ${file.path}")
            }
            val text = bytes.toString(Charsets.UTF_8)
            when {
                file.path == DOCUMENT -> {
                    try {
                        DocumentEnvelope.parse(text)
                    } catch (e: EnvelopeFormatException) {
                        throw BackupFormatException("document.json is not a valid document", e)
                    }
                    document = text
                }
                else -> {
                    val name = MOBILE_PATH.matchEntire(file.path)?.groupValues?.get(1)
                        ?: throw BackupFormatException("Unexpected file in manifest: ${file.path}")
                    mobileOnly[name] = text
                }
            }
        }
        return BackupContents(manifest.appId, createdAt, document, mobileOnly)
    }

    private fun readEntries(input: InputStream, maxEntryBytes: Long): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        try {
            val zip = ZipInputStream(input)
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || !isAllowedName(name)) throw BackupFormatException("Unexpected entry: '$name'")
                if (entries.size >= MAX_ENTRIES) throw BackupFormatException("Too many entries")
                if (name in entries) throw BackupFormatException("Duplicate entry: '$name'")
                entries[name] = readLimited(zip, maxEntryBytes, name)
            }
        } catch (e: IOException) {
            throw BackupFormatException("Not a valid archive", e)
        }
        return entries
    }

    private fun isAllowedName(name: String): Boolean =
        name == MANIFEST || name == DOCUMENT || MOBILE_PATH.matches(name)

    private fun readLimited(zip: ZipInputStream, max: Long, name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            total += read
            if (total > max) throw BackupFormatException("Entry '$name' is too large")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.putEntry(name: String, bytes: ByteArray, time: Instant) {
        val entry = ZipEntry(name)
        entry.time = time.toEpochMilli()
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
