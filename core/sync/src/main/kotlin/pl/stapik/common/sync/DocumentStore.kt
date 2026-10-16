package pl.stapik.common.sync

import java.time.Instant
import pl.stapik.common.model.DocumentEnvelope
import pl.stapik.common.model.LocalDocument
import pl.stapik.common.model.SlotKey

enum class ConflictSource { LOCAL, CLOUD }

/**
 * The losing side of an automatic conflict resolution, kept so that nothing is lost silently.
 *
 * @property content the full envelope text of the version that lost.
 */
data class ConflictCopy(
    val slot: SlotKey,
    val source: ConflictSource,
    val createdAt: Instant,
    val content: String,
)

/**
 * Durable storage of one synced document on the device. The real implementation (atomic file,
 * encryption at rest, rotating backups) comes later in `core:document`.
 *
 * The stored content is the whole [DocumentEnvelope], not the app's model, so unknown fields
 * survive a save.
 */
interface DocumentStore {
    suspend fun load(): LocalDocument<DocumentEnvelope>?

    suspend fun save(document: LocalDocument<DocumentEnvelope>)

    suspend fun saveConflictCopy(copy: ConflictCopy)
}
