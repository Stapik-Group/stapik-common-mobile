package pl.stapik.common.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncToneTest {
    @Test
    fun `problems that need the user are warnings or errors`() {
        assertEquals(SyncTone.MUTED, SyncBadge.SYNCED.tone)
        assertEquals(SyncTone.ACTIVE, SyncBadge.SYNCING.tone)
        assertEquals(SyncTone.WARNING, SyncBadge.OFFLINE.tone)
        assertEquals(SyncTone.WARNING, SyncBadge.CONFLICT_RESOLVED.tone)
        assertEquals(SyncTone.ERROR, SyncBadge.UNREADABLE.tone)
        assertEquals(SyncTone.ERROR, SyncBadge.AUTH.tone)
    }

    @Test
    fun `actions come in the declared order`() {
        val state = SyncUiState(SyncBadge.OFFLINE, "k", setOf(SyncAction.UPDATE_APP, SyncAction.RETRY))
        assertEquals(listOf(SyncAction.RETRY, SyncAction.UPDATE_APP), state.orderedActions)
    }

    @Test
    fun `only the synced state is quiet`() {
        assertTrue(SyncUiState(SyncBadge.SYNCED, "k").isQuiet)
        assertFalse(SyncUiState(SyncBadge.LOCAL_ONLY, "k").isQuiet)
        assertFalse(SyncUiState(SyncBadge.CONFLICT_RESOLVED, "k").isQuiet)
    }
}
