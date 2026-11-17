package net.tdobson.groqvoice.core.net

/**
 * Network timeouts. The desktop uses a flat 30 s per call; on a phone a long recording can take longer than
 * that to upload, so the call timeout grows with the file size. Read and write timeouts are OkHttp inactivity
 * timeouts, so a slow but progressing upload is never cut off by them.
 */
class TimeoutPolicy(
    val connectTimeoutMs: Long = 10_000,
    val baseCallTimeoutMs: Long = 30_000,
    val uploadBytesPerSecond: Long = 20L * 1024,
    val inactivityTimeoutMs: Long = 30_000,
) {
    /** base + fileBytes / uploadBytesPerSecond, negative sizes treated as 0. */
    fun callTimeoutMs(fileBytes: Long): Long =
        baseCallTimeoutMs + fileBytes.coerceAtLeast(0) * 1_000 / uploadBytesPerSecond
}
