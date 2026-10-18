package pl.stapik.common.app

import java.time.Clock
import pl.stapik.common.document.BackupContents
import pl.stapik.common.sync.DocumentStore

/**
 * Reads everything an app keeps - the synced document and the device-only stores - as one
 * [BackupContents], for export and for safety copies.
 */
class AppBackup(
    private val appId: String,
    private val store: DocumentStore,
    private val mobileOnly: Map<String, MobileOnlyPort> = emptyMap(),
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun snapshot(): BackupContents =
        BackupContents(
            appId = appId,
            createdAt = clock.instant(),
            document = store.load()?.content?.toJson(),
            mobileOnly = mobileOnly.mapValues { (_, port) -> port.exportJson() },
        )
}
