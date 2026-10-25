package pl.stapik.common.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncRetryPolicyTest {
    private val offline = SyncOutcome.Offline(CloudError.Network("down"))

    @Test
    fun `only an unreachable cloud is worth retrying`() {
        val others = listOf(
            SyncOutcome.LocalOnly,
            SyncOutcome.Synchronized,
            SyncOutcome.ServerWon,
            SyncOutcome.LocalWon,
            SyncOutcome.KeptLocalUnsynced,
            SyncOutcome.AuthProblem(CloudError.Unauthorized),
            SyncOutcome.NeedsFirstConnectionChoice(true),
            SyncOutcome.RemoteUnreadable("x"),
            SyncOutcome.NewerSchema(2, 1),
        )
        assertEquals(emptyList<SyncOutcome>(), others.filter(SyncRetryPolicy::isWorthRetrying))
        listOf(
            CloudError.Network(null),
            CloudError.ServerError(503),
            CloudError.RateLimited(120),
            CloudError.Conflict,
        ).forEach { assertEquals(true, SyncRetryPolicy.isWorthRetrying(SyncOutcome.Offline(it))) }
    }

    @Test
    fun `retrying stops after the attempt limit`() {
        assertEquals(true, SyncRetryPolicy.shouldTryAgain(offline, attemptsMade = 4, maxAttempts = 5))
        assertEquals(false, SyncRetryPolicy.shouldTryAgain(offline, attemptsMade = 5, maxAttempts = 5))
        assertEquals(false, SyncRetryPolicy.shouldTryAgain(SyncOutcome.Synchronized, attemptsMade = 0))
    }
}
