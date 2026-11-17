package net.tdobson.groqvoice.core.retry

/**
 * Desktop parity with voiceTyping.js withRetry(): MAX_RETRIES=10, INITIAL_BACKOFF_MS=1000, MAX_BACKOFF_MS=60000.
 * Attempt numbers are 1-based: failedAttempt = 1 means the first call just failed.
 */
class RetryPolicy(
    val maxAttempts: Int = 10,
    val initialBackoffMs: Long = 1_000,
    val maxBackoffMs: Long = 60_000,
    val maxRetryAfterMs: Long = 60_000,
) {
    /** min(initial * 2^(failedAttempt-1), max). */
    fun backoffAfter(failedAttempt: Int): Long {
        val shift = (failedAttempt - 1).coerceIn(0, 62)
        // Once the doubling passes the cap (or would overflow) the answer is the cap.
        if (initialBackoffMs > maxBackoffMs shr shift) return maxBackoffMs
        return minOf(initialBackoffMs shl shift, maxBackoffMs)
    }

    fun hasAttemptsLeft(failedAttempt: Int): Boolean = failedAttempt < maxAttempts

    /** Like the desktop, only the first failure waits for connectivity instead of sleeping. */
    fun waitForConnectivityAfter(failedAttempt: Int): Boolean = failedAttempt == 1

    /** Sleep before the next attempt: retry-after (capped) if given; else 0 after attempt 1; else the backoff. */
    fun delayAfter(failedAttempt: Int, retryAfterMs: Long?): Long = when {
        retryAfterMs != null -> retryAfterMs.coerceIn(0, maxRetryAfterMs)
        waitForConnectivityAfter(failedAttempt) -> 0
        else -> backoffAfter(failedAttempt)
    }
}
