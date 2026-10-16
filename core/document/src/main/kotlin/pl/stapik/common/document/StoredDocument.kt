package pl.stapik.common.document

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pl.stapik.common.model.CloudScope
import pl.stapik.common.model.DocumentEnvelope
import pl.stapik.common.model.LocalDocument

/** The on-disk form of a [LocalDocument]. Versioned so the format can change later. */
@Serializable
internal data class StoredDocument(
    val version: Int,
    /** The whole envelope text, exactly as it would be sent to the cloud. */
    val content: String,
    val cloudBaseline: String = "",
    val dirty: Boolean = false,
    val modifiedAt: String? = null,
    val scope: String? = null,
    val cloudIdentity: String? = null,
) {
    fun toLocalDocument(): LocalDocument<DocumentEnvelope> {
        require(version == CURRENT_VERSION) { "Unsupported stored document version: $version" }
        return LocalDocument(
            content = DocumentEnvelope.parse(content),
            cloudBaseline = cloudBaseline,
            dirty = dirty,
            modifiedAt = modifiedAt?.let(Instant::parse),
            scope = scope?.let { CloudScope.valueOf(it) },
            cloudIdentity = cloudIdentity,
        )
    }

    companion object {
        const val CURRENT_VERSION = 1

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun from(document: LocalDocument<DocumentEnvelope>) = StoredDocument(
            version = CURRENT_VERSION,
            content = document.content.toJson(),
            cloudBaseline = document.cloudBaseline,
            dirty = document.dirty,
            modifiedAt = document.modifiedAt?.toString(),
            scope = document.scope?.name,
            cloudIdentity = document.cloudIdentity,
        )

        fun encode(document: LocalDocument<DocumentEnvelope>): ByteArray =
            json.encodeToString(serializer(), from(document)).toByteArray(Charsets.UTF_8)

        fun decode(bytes: ByteArray): LocalDocument<DocumentEnvelope> =
            json.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8)).toLocalDocument()
    }
}
