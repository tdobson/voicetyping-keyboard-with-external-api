package net.tdobson.groqvoice.core.retry

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object RetryAfter {
    private val SECONDS = Regex("""\d+(\.\d+)?""")
    private val LONG_MAX = Long.MAX_VALUE.toBigDecimal()

    /** Parses a Retry-After header: delta-seconds (integer or decimal) or an HTTP-date. Null if absent/invalid/negative. Past dates give 0. */
    fun parseMs(header: String?, nowMs: Long): Long? {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (SECONDS.matches(value)) {
            val ms = value.toBigDecimal().movePointRight(3)
            return if (ms > LONG_MAX) Long.MAX_VALUE else ms.toLong()
        }
        return try {
            val at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (at - nowMs).coerceAtLeast(0)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
