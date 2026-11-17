package net.tdobson.groqvoice.core.retry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class RetryAfterTest {
    private val now = 1_763_370_000_000L // a fixed instant, whole seconds

    private fun httpDate(ms: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC))

    @Test fun `integer seconds`() = assertEquals(5_000L, RetryAfter.parseMs("5", now))
    @Test fun `decimal seconds`() = assertEquals(2_500L, RetryAfter.parseMs("2.5", now))
    @Test fun `surrounding whitespace is ignored`() = assertEquals(7_000L, RetryAfter.parseMs(" 7 ", now))
    @Test fun `zero seconds`() = assertEquals(0L, RetryAfter.parseMs("0", now))
    @Test fun `negative is invalid`() = assertNull(RetryAfter.parseMs("-1", now))
    @Test fun `garbage is invalid`() = assertNull(RetryAfter.parseMs("abc", now))
    @Test fun `absent header`() = assertNull(RetryAfter.parseMs(null, now))
    @Test fun `empty header`() = assertNull(RetryAfter.parseMs("", now))
    @Test fun `huge delta does not overflow into a short wait`() =
        assertEquals(Long.MAX_VALUE, RetryAfter.parseMs("99999999999999999999999", now))
    @Test fun `http date ten seconds ahead`() = assertEquals(10_000L, RetryAfter.parseMs(httpDate(now + 10_000), now))
    @Test fun `http date in the past gives zero`() = assertEquals(0L, RetryAfter.parseMs(httpDate(now - 30_000), now))
}
