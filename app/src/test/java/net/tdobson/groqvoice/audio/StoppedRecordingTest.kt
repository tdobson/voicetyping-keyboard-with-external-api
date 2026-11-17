package net.tdobson.groqvoice.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layer 1: which stopped recordings Recorder keeps. "Unsent recordings are never lost" wins over a tidy file. */
class StoppedRecordingTest {
    private fun keep(stopThrew: Boolean, maxReached: Boolean = false, durationMs: Long, bytes: Long) =
        StoppedRecording.keep(stopThrew, maxReached, durationMs, bytes)

    @Test fun `a clean stop keeps anything at least 250 ms long`() {
        assertTrue(keep(stopThrew = false, durationMs = 250, bytes = 0))
        assertTrue(keep(stopThrew = false, durationMs = 180_000, bytes = 540_000))
    }

    @Test fun `a clean stop under 250 ms is nothing recorded`() =
        assertFalse(keep(stopThrew = false, durationMs = 249, bytes = 5_000))

    @Test fun `stop throwing after minutes of audio keeps the file`() =
        assertTrue(keep(stopThrew = true, durationMs = 180_000, bytes = 540_000))

    @Test fun `stop throwing right after start (no audio yet) drops it`() {
        assertFalse(keep(stopThrew = true, durationMs = 50, bytes = 0))
        assertFalse(keep(stopThrew = true, durationMs = 50, bytes = 4_000))
    }

    @Test fun `stop throwing with 1 KB or less on disk drops it`() {
        assertFalse(keep(stopThrew = true, durationMs = 2_000, bytes = 1_024))
        assertTrue(keep(stopThrew = true, durationMs = 2_000, bytes = 1_025))
    }

    @Test fun `stop throwing after the max-duration stop keeps the final file`() =
        assertTrue(keep(stopThrew = true, maxReached = true, durationMs = 3_600_000, bytes = 10_800_000))
}
