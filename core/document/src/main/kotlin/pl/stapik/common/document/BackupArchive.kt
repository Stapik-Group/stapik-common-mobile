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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * @property appVersion version of the app that made the archive (the desktop requires the field).
 */
data class BackupContents(
    val appId: String,
    val createdAt: Instant,
    val document: String?,
    val mobileOnly: Map<String, String> = emptyMap(),
    val appVersion: String = "",
)

/**
 * Manual export/import format (plan 4.6), the same ZIP as the desktop's `stapik-archive`
 * (`stapik-common/docs/archive.md`), so the document can travel between phone and desktop in both
 * directions.
 *
 * - `manifest.json`: `format` ("stapik-archive"), `formatVersion`, `app`, `appVersion`, `createdAt`,
 *   `assets` (always empty here) as on the desktop, plus `files`: path, size and SHA-256 of every
 *   entry this library writes (`document.json`, `mobile-only/<name>.json`).
 * - `document.json`: the document exactly as in the cloud.
 * - `mobile-only/<name>.json`: device-only data. The desktop ignores these entries.
 *
 * Reading is defensive: only known entry names are accepted (no paths, so no path traversal), the
 * number and size of entries are capped, and the document must be a valid envelope. An archive made
 * by this library (it has `files`) must match its manifest exactly. An archive made by the desktop
 * (no `files`) is accepted for its `document.json`; its `settings.json` and `assets/` are skipped,
 * since phones keep neither.
 *
 * Not included here: encryption. A protected export wraps the whole file (see `BackupSealing`).
 */
object BackupArchive {
    const val FORMAT_VERSION = 1
    const val FORMAT_NAME = "stapik-archive"
    const val DEFAULT_MAX_ENTRY_BYTES: Long = 32L * 1024 * 1024

    private const val MANIFEST = "manifest.json"
    private const val DOCUMENT = "document.json"
    private const val DESKTOP_SETTINGS = "settings.json"
    private const val DESKTOP_ASSETS_PREFIX = "assets/"
    private const val MOBILE_PREFIX = "mobile-only/"
    private const val MAX_ENTRIES = 200
    private val NAME = Regex("[A-Za-z0-9._-]{1,100}")
    private val MOBILE_PATH = Regex("mobile-only/([A-Za-z0-9._-]{1,100})\\.json")

    /** Writes the archive to [out]. Does not close [out]. */
    fun write(out: OutputStream, contents: BackupContents) {
        val files = LinkedHashMap<String, ByteArray>()
        contents.document?.let { files[DOCUMENT] = it.toByteArray(Charsets.UTF_8) }
        for ((name, text) in contents.mobileOnly.toSortedMap()) {
            require(NAME.matches(name)) { "Invalid store name: '$name'" }
            files["$MOBILE_PREFIX$name.json"] = text.toByteArray(Charsets.UTF_8)
        }
        val createdAt = contents.createdAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val manifest = JsonObject(
            mapOf(
                "format" to JsonPrimitive(FORMAT_NAME),
                "formatVersion" to JsonPrimitive(FORMAT_VERSION),
                "app" to JsonPrimitive(contents.appId),
                "appVersion" to JsonPrimitive(contents.appVersion),
                "createdAt" to JsonPrimitive(createdAt.toString()),
                "assets" to JsonArray(emptyList()),
                "files" to JsonArray(
                    files.map { (path, bytes) ->
                        JsonObject(
                            mapOf(
                                "path" to JsonPrimitive(path),
                                "sha256" to JsonPrimitive(sha256(bytes)),
                                "size" to JsonPrimitive(bytes.size.toLong()),
                            ),
                        )
                    },
                ),
            ),
        )
        val zip = ZipOutputStream(out)
        zip.putEntry(MANIFEST, manifest.toString().toByteArray(Charsets.UTF_8), createdAt)
        for ((path, bytes) in files) zip.putEntry(path, bytes, createdAt)
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
            Json.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8)) as? JsonObject
                ?: throw BackupFormatException("manifest.json is not valid")
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("manifest.json is not valid", e)
        }
        if (text(manifest, "format") != FORMAT_NAME) throw BackupFormatException("This is not a Stapik archive")
        val formatVersion = (manifest["formatVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: throw BackupFormatException("manifest.json has no format version")
        if (formatVersion > FORMAT_VERSION) {
            throw BackupFormatException("The archive was made by a newer version of the application")
        }
        val appId = text(manifest, "app") ?: throw BackupFormatException("manifest.json has no app name")
        val appVersion = text(manifest, "appVersion").orEmpty()
        val createdAt = try {
            Instant.parse(text(manifest, "createdAt") ?: throw BackupFormatException("manifest.json has no creation time"))
        } catch (e: DateTimeParseException) {
            throw BackupFormatException("Invalid creation time in manifest", e)
        }

        val files = manifest["files"]
        return if (files == null) {
            readDesktopArchive(entries, appId, appVersion, createdAt)
        } else {
            readOwnArchive(entries, files, appId, appVersion, createdAt)
        }
    }

    /** An archive made by the desktop: only `document.json` is used. */
    private fun readDesktopArchive(
        entries: Map<String, ByteArray>,
        appId: String,
        appVersion: String,
        createdAt: Instant,
    ): BackupContents {
        if (entries.keys.any { MOBILE_PATH.matches(it) }) throw BackupFormatException("Archive has unlisted files")
        val bytes = entries[DOCUMENT] ?: throw BackupFormatException("document.json is missing")
        return BackupContents(appId, createdAt, parseDocument(bytes), emptyMap(), appVersion)
    }

    /** An archive made by this library: every entry must match the manifest. */
    private fun readOwnArchive(
        entries: Map<String, ByteArray>,
        filesElement: kotlinx.serialization.json.JsonElement,
        appId: String,
        appVersion: String,
        createdAt: Instant,
    ): BackupContents {
        val list = (filesElement as? JsonArray) ?: throw BackupFormatException("manifest.json is not valid")
        class Listed(val path: String, val sha256: String, val size: Long)
        val listed = list.map { element ->
            val obj = element as? JsonObject ?: throw BackupFormatException("manifest.json is not valid")
            Listed(
                text(obj, "path") ?: throw BackupFormatException("manifest.json is not valid"),
                text(obj, "sha256") ?: throw BackupFormatException("manifest.json is not valid"),
                (obj["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: throw BackupFormatException("manifest.json is not valid"),
            )
        }
        val paths = listed.map { it.path }
        if (paths.toSet().size != paths.size) throw BackupFormatException("Manifest lists a file twice")
        val unlisted = entries.keys - MANIFEST - paths.toSet()
        if (unlisted.isNotEmpty()) throw BackupFormatException("Archive has unlisted files: $unlisted")

        var document: String? = null
        val mobileOnly = LinkedHashMap<String, String>()
        for (file in listed) {
            val bytes = entries[file.path] ?: throw BackupFormatException("Listed file is missing: ${file.path}")
            if (bytes.size.toLong() != file.size || sha256(bytes) != file.sha256) {
                throw BackupFormatException("File does not match the manifest: ${file.path}")
            }
            if (file.path == DOCUMENT) {
                document = parseDocument(bytes)
            } else {
                val name = MOBILE_PATH.matchEntire(file.path)?.groupValues?.get(1)
                    ?: throw BackupFormatException("Unexpected file in manifest: ${file.path}")
                mobileOnly[name] = bytes.toString(Charsets.UTF_8)
            }
        }
        return BackupContents(appId, createdAt, document, mobileOnly, appVersion)
    }

    private fun parseDocument(bytes: ByteArray): String {
        val text = bytes.toString(Charsets.UTF_8)
        try {
            DocumentEnvelope.parse(text)
        } catch (e: EnvelopeFormatException) {
            throw BackupFormatException("document.json is not a valid document", e)
        }
        return text
    }

    private fun text(obj: JsonObject, name: String): String? =
        (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Entries that are read; desktop-only entries (`settings.json`, `assets/...`) are skipped unread. */
    private fun readEntries(input: InputStream, maxEntryBytes: Long): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        try {
            val zip = ZipInputStream(input)
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (name == DESKTOP_SETTINGS || name.startsWith(DESKTOP_ASSETS_PREFIX)) continue
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
