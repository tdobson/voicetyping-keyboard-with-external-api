package net.tdobson.groqvoice.core.net

import kotlinx.coroutines.delay

/**
 * Desktop waitForConnectivity(): probe, and if unreachable wait [intervalMs] and probe again, until cancelled.
 * [beforeEachProbe] runs (and may suspend) before every probe, e.g. to wait for a validated network again.
 */
suspend fun awaitReachable(
    probe: suspend () -> Boolean,
    intervalMs: Long = 3_000,
    beforeEachProbe: suspend () -> Unit = {},
) {
    while (true) {
        beforeEachProbe()
        if (probe()) return
        delay(intervalMs)
    }
}
