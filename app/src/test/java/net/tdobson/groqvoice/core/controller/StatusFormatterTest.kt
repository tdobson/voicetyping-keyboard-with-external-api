package net.tdobson.groqvoice.core.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusFormatterTest {
    private fun text(s: Status, now: Long = 0) = StatusFormatter.text(s, now)

    @Test fun `idle hint`() = assertEquals("Hold to talk, slide up to lock", text(Status.Idle))

    @Test fun `recording shows elapsed minutes and seconds`() {
        assertEquals("Recording 0:12", text(Status.Recording(1_000, locked = false), now = 13_345))
        assertEquals("Recording 60:05", text(Status.Recording(0, locked = false), now = 3_605_000))
    }

    @Test fun `recording time uses ascii digits whatever the phone locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("Recording 0:12", text(Status.Recording(0, locked = false), now = 12_345))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test fun `recording never shows negative time`() =
        assertEquals("Recording 0:00", text(Status.Recording(5_000, locked = false), now = 4_000))

    @Test fun `locked recording tells you how to send`() =
        assertEquals("Recording 0:12, tap Stop to send", text(Status.Recording(0, locked = true), now = 12_345))

    @Test fun `in-flight statuses`() {
        assertEquals("Transcribing...", text(Status.Transcribing))
        assertEquals("Retrying (3/10)...", text(Status.Retrying(3, 10)))
        assertEquals("Waiting for signal...", text(Status.WaitingForSignal))
    }

    @Test fun `outcome statuses`() {
        assertEquals("Text ready, tap ↺ to insert", text(Status.TextReady))
        assertEquals("Nothing heard", text(Status.NothingHeard))
        assertEquals("Hold to talk", text(Status.HoldToTalk))
        assertEquals("Add your Groq API key in Groq Voice settings", text(Status.NoApiKey))
        assertEquals("Allow the microphone in Groq Voice settings", text(Status.NoMicPermission))
        assertEquals("API key rejected, open settings", text(Status.AuthRejected))
        assertEquals("Groq: file too short", text(Status.Rejected("file too short")))
        assertEquals("Couldn't send after 10 tries, saved as unsent", text(Status.GaveUp(10)))
        assertEquals("Cancelled, saved as unsent", text(Status.Cancelled))
        assertEquals("Recording failed", text(Status.RecordingFailed))
    }

    @Test fun `rate limited says how long to wait, rounded up`() {
        assertEquals("Groq rate limit, try again in 2 min, saved as unsent", text(Status.RateLimited(61_000)))
        assertEquals("Groq rate limit, try again in 59 min, saved as unsent", text(Status.RateLimited(3_540_000)))
        assertEquals("Groq rate limit, try again in 1 h, saved as unsent", text(Status.RateLimited(3_600_000)))
        assertEquals("Groq rate limit, try again in 3 h, saved as unsent", text(Status.RateLimited(7_300_000)))
    }

    @Test fun `unsent chip`() {
        assertNull(StatusFormatter.unsentChip(0))
        assertEquals("⟳ 2 unsent", StatusFormatter.unsentChip(2))
    }

    @Test fun `cancel is offered exactly while sending`() {
        val inFlight = setOf(Status.Transcribing, Status.Retrying(2, 10), Status.WaitingForSignal)
        val all = inFlight + setOf(
            Status.Idle, Status.Recording(0, false), Status.Recording(0, true), Status.TextReady, Status.NothingHeard,
            Status.HoldToTalk, Status.NoApiKey, Status.NoMicPermission, Status.AuthRejected, Status.Rejected("x"),
            Status.GaveUp(10), Status.Cancelled, Status.RecordingFailed, Status.RateLimited(3_600_000),
        )
        for (s in all) {
            if (s in inFlight) assertTrue("$s", StatusFormatter.showsCancel(s)) else assertFalse("$s", StatusFormatter.showsCancel(s))
        }
    }
}
