package net.tdobson.groqvoice.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeoutPolicyTest {
    private val t = TimeoutPolicy()

    @Test fun `connect timeout is ten seconds`() = assertEquals(10_000L, t.connectTimeoutMs)
    @Test fun `empty file gets the thirty second base`() = assertEquals(30_000L, t.callTimeoutMs(0))
    @Test fun `twenty KiB adds one second`() = assertEquals(31_000L, t.callTimeoutMs(20_480))
    @Test fun `one MiB adds 51 point 2 seconds`() = assertEquals(81_200L, t.callTimeoutMs(1_048_576))
    @Test fun `negative size is treated as zero`() = assertEquals(30_000L, t.callTimeoutMs(-5))

    @Test fun `sixty minutes at 24 kbps stays under ten minutes`() {
        val ms = t.callTimeoutMs(10_800_000)
        assertEquals(557_343L, ms)
        assertTrue(ms < 600_000)
    }

    @Test fun `okhttp inactivity timeouts are thirty seconds`() = assertEquals(30_000L, t.inactivityTimeoutMs)
}
