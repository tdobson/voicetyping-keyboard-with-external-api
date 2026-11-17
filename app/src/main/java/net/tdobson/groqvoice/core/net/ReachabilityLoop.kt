package net.tdobson.groqvoice.core.net

import kotlinx.coroutines.delay

/** Desktop waitForConnectivity(): probe, and if unreachable wait [intervalMs] and probe again, until cancelled. */
suspend fun awaitReachable(probe: suspend () -> Boolean, intervalMs: Long = 3_000) {
    while (!probe()) delay(intervalMs)
}
