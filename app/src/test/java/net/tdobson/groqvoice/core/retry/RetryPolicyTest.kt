package net.tdobson.groqvoice.core.retry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    private val p = RetryPolicy()

    @Test fun `ten attempts like desktop`() = assertEquals(10, p.maxAttempts)

    @Test fun `backoff doubles from one second and caps at sixty`() {
        assertEquals(
            listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000, 60_000),
            (1..9).map(p::backoffAfter),
        )
    }

    @Test fun `backoff stays capped for very large attempt numbers`() {
        assertEquals(60_000L, p.backoffAfter(64))
        assertEquals(60_000L, p.backoffAfter(1_000))
    }

    @Test fun `attempts left until the tenth failure`() {
        assertTrue(p.hasAttemptsLeft(9))
        assertFalse(p.hasAttemptsLeft(10))
    }

    @Test fun `connectivity wait only after first failure`() {
        assertTrue(p.waitForConnectivityAfter(1))
        assertFalse(p.waitForConnectivityAfter(2))
    }

    @Test fun `no sleep after first failure because connectivity wait replaces it`() =
        assertEquals(0L, p.delayAfter(1, null))

    @Test fun `later failures sleep the backoff`() = assertEquals(4_000L, p.delayAfter(3, null))

    @Test fun `retry-after wins over backoff on any attempt`() {
        assertEquals(5_000L, p.delayAfter(1, 5_000))
        assertEquals(5_000L, p.delayAfter(4, 5_000))
    }

    @Test fun `retry-after is capped at sixty seconds`() = assertEquals(60_000L, p.delayAfter(2, 3_600_000))

    @Test fun `full failure sleeps 242 seconds like desktop`() =
        assertEquals(242_000L, (1..9).sumOf { p.delayAfter(it, null) })
}
