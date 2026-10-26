package pl.stapik.common.sync

/** Schedules one more synchronisation later, when the last one could not reach the cloud. */
interface SyncRetry {
    fun schedule()
    fun cancel()

    companion object {
        val None: SyncRetry = object : SyncRetry {
            override fun schedule() = Unit
            override fun cancel() = Unit
        }
    }
}

object SyncRetryPolicy {
    const val DEFAULT_MAX_ATTEMPTS = 5

    /** Only a cloud that could not be reached is worth another try; every other outcome needs the user or is final. */
    fun isWorthRetrying(outcome: SyncOutcome): Boolean = outcome is SyncOutcome.Offline

    fun shouldTryAgain(outcome: SyncOutcome, attemptsMade: Int, maxAttempts: Int = DEFAULT_MAX_ATTEMPTS): Boolean =
        isWorthRetrying(outcome) && attemptsMade < maxAttempts
}
