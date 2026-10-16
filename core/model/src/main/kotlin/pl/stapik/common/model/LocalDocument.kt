package pl.stapik.common.model

import java.time.Instant

/**
 * The local copy of a cloud-synced document together with what is needed to sync it.
 *
 * Immutable; every change returns a new instance. It holds no I/O and no clock: the caller passes
 * the time in, so the sync logic stays deterministic in tests.
 *
 * @property content the app's domain document.
 * @property cloudBaseline `updatedAt` of the last synchronisation; empty means never synchronised.
 * @property dirty there are local changes not yet sent to the cloud.
 * @property modifiedAt when the document was last changed locally (input to the conflict policy).
 * @property scope key scope seen at the last contact with the cloud, if any.
 * @property cloudIdentity which cloud the baseline belongs to (URL + extensionId), if known.
 */
data class LocalDocument<T>(
    val content: T,
    val cloudBaseline: String = "",
    val dirty: Boolean = false,
    val modifiedAt: Instant? = null,
    val scope: CloudScope? = null,
    val cloudIdentity: String? = null,
) {
    val everSynchronized: Boolean get() = cloudBaseline.isNotEmpty()

    /** A local edit: replaces the content and marks the document as not yet sent. */
    fun edited(newContent: T, now: Instant): LocalDocument<T> =
        copy(content = newContent, dirty = true, modifiedAt = now)

    /** The cloud confirmed (or supplied) this content: it becomes the new baseline and is clean. */
    fun synchronized(
        newContent: T,
        updatedAt: String,
        scope: CloudScope?,
        cloudIdentity: String?,
    ): LocalDocument<T> {
        require(updatedAt.isNotEmpty()) { "A synchronised document needs a non-empty updatedAt" }
        return copy(
            content = newContent,
            cloudBaseline = updatedAt,
            dirty = false,
            scope = scope,
            cloudIdentity = cloudIdentity,
        )
    }

    /** Forgets the cloud (disconnect or a different cloud): content stays, baseline is cleared. */
    fun detachedFromCloud(): LocalDocument<T> =
        copy(cloudBaseline = "", scope = null, cloudIdentity = null)
}
