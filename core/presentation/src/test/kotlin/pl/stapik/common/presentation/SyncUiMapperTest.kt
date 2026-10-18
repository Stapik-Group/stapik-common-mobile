package pl.stapik.common.presentation

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.stapik.common.sync.CloudError
import pl.stapik.common.sync.SyncOutcome
import pl.stapik.common.sync.SyncStatus
import pl.stapik.common.sync.UnreadableKind

class SyncUiMapperTest {
    private fun map(last: SyncOutcome?, pending: Boolean = false, connected: Boolean = true, status: SyncStatus = SyncStatus.IDLE) =
        SyncUiMapper.map(status, last, pending, connected)

    @Test
    fun `syncing wins`() {
        assertEquals(SyncBadge.SYNCING, map(SyncOutcome.Synchronized, status = SyncStatus.SYNCING).badge)
    }

    @Test
    fun `no cloud is local only whatever happened`() {
        assertEquals(SyncBadge.LOCAL_ONLY, map(SyncOutcome.Offline(CloudError.Network()), connected = false).badge)
    }

    @Test
    fun `before the first sync`() {
        assertEquals(SyncBadge.SYNCED, map(null).badge)
        assertEquals(SyncUiState(SyncBadge.PENDING, "sync.pending", setOf(SyncAction.RETRY)), map(null, pending = true))
    }

    @Test
    fun `resolved conflicts are synced with their own message`() {
        assertEquals("sync.resolved.server", map(SyncOutcome.ServerWon).messageKey)
        assertEquals("sync.resolved.local", map(SyncOutcome.LocalWon).messageKey)
        assertEquals(SyncBadge.SYNCED, map(SyncOutcome.LocalWon).badge)
    }

    @Test
    fun `read only key`() {
        assertEquals(SyncUiState(SyncBadge.READ_ONLY, "sync.read_only", setOf(SyncAction.RECONNECT)), map(SyncOutcome.KeptLocalUnsynced))
    }

    @Test
    fun `offline reasons`() {
        assertEquals("sync.offline.network", map(SyncOutcome.Offline(CloudError.Network("x"))).messageKey)
        assertEquals("sync.offline.server", map(SyncOutcome.Offline(CloudError.ServerError(503))).messageKey)
        assertEquals("sync.offline.conflict", map(SyncOutcome.Offline(CloudError.Conflict)).messageKey)
        val limited = map(SyncOutcome.Offline(CloudError.RateLimited(120)))
        assertEquals("sync.offline.rate_limited", limited.messageKey)
        assertEquals(120L, limited.retryAfterSeconds)
        assertEquals(setOf(SyncAction.RETRY), limited.actions)
    }

    @Test
    fun `auth problems ask to reconnect`() {
        assertEquals("sync.auth.unauthorized", map(SyncOutcome.AuthProblem(CloudError.Unauthorized)).messageKey)
        assertEquals("sync.auth.forbidden", map(SyncOutcome.AuthProblem(CloudError.Forbidden)).messageKey)
        assertEquals(setOf(SyncAction.RECONNECT), map(SyncOutcome.AuthProblem(CloudError.Forbidden)).actions)
    }

    @Test
    fun `first connection asks which side to keep`() {
        assertEquals(setOf(SyncAction.CHOOSE_SOURCE), map(SyncOutcome.NeedsFirstConnectionChoice(true)).actions)
    }

    @Test
    fun `unreadable cloud data asks for the right thing`() {
        fun actions(kind: UnreadableKind) = map(SyncOutcome.RemoteUnreadable("r", kind)).actions
        assertEquals(setOf(SyncAction.ENTER_PASSPHRASE), actions(UnreadableKind.PASSPHRASE_REQUIRED))
        assertEquals(setOf(SyncAction.ENTER_PASSPHRASE), actions(UnreadableKind.DECRYPTION_FAILED))
        assertEquals(setOf(SyncAction.UPDATE_APP), actions(UnreadableKind.UNSUPPORTED_VERSION))
        assertEquals(setOf(SyncAction.RETRY), actions(UnreadableKind.OTHER))
        assertEquals("sync.unreadable.wrong_passphrase", map(SyncOutcome.RemoteUnreadable("r", UnreadableKind.DECRYPTION_FAILED)).messageKey)
    }

    @Test
    fun `newer schema asks to update the app`() {
        assertEquals(SyncUiState(SyncBadge.NEWER_SCHEMA, "sync.newer_schema", setOf(SyncAction.UPDATE_APP)), map(SyncOutcome.NewerSchema(2, 1)))
    }
}
