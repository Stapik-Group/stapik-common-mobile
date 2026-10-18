package pl.stapik.common.model

/**
 * Tells whether a document was written with a newer schema than this version of the app
 * understands. Such a document may be shown, but not edited: the typed model would silently drop
 * what it does not know (see [DocumentCodec]).
 *
 * @param supported the highest schema version this app understands.
 * @param readVersion reads the schema version from an envelope; null when it has none (then it is
 *   never "newer"). Where the version lives is the app's business.
 */
class SchemaGate(
    val supported: Int,
    private val readVersion: (DocumentEnvelope) -> Int?,
) {
    fun versionOf(envelope: DocumentEnvelope): Int? = readVersion(envelope)

    /** The version of [envelope] if it is newer than [supported], else null. */
    fun newerVersionOf(envelope: DocumentEnvelope): Int? = versionOf(envelope)?.takeIf { it > supported }
}
