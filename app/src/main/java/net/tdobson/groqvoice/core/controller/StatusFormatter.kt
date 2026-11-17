package net.tdobson.groqvoice.core.controller

import java.util.Locale

/** Exact status-line strings, so the view and the emulator proof can rely on them. */
object StatusFormatter {
    fun text(status: Status, nowMs: Long): String = when (status) {
        Status.Idle -> "Hold to talk, slide up to lock"
        is Status.Recording -> {
            val elapsed = "Recording " + clock(nowMs - status.startedAtMs)
            if (status.locked) "$elapsed, tap Stop to send" else elapsed
        }
        Status.Transcribing -> "Transcribing..."
        is Status.Retrying -> "Retrying (${status.attempt}/${status.maxAttempts})..."
        Status.WaitingForSignal -> "Waiting for signal..."
        Status.TextReady -> "Text ready, tap ↺ to insert"
        Status.NothingHeard -> "Nothing heard"
        Status.HoldToTalk -> "Hold to talk"
        Status.NoApiKey -> "Add your Groq API key in Groq Voice settings"
        Status.NoMicPermission -> "Allow the microphone in Groq Voice settings"
        Status.AuthRejected -> "API key rejected, open settings"
        is Status.Rejected -> "Groq: ${status.message}"
        is Status.GaveUp -> "Couldn't send after ${status.attempts} tries, saved as unsent"
        is Status.RateLimited -> "Groq rate limit, try again in ${waitText(status.retryAfterMs)}, saved as unsent"
        Status.Cancelled -> "Cancelled, saved as unsent"
        Status.RecordingFailed -> "Recording failed"
    }

    fun unsentChip(count: Int): String? = if (count > 0) "⟳ $count unsent" else null

    /** The ✕ cancel is offered while a recording is being sent. */
    fun showsCancel(status: Status): Boolean =
        status == Status.Transcribing || status is Status.Retrying || status == Status.WaitingForSignal

    /** Minutes rounded up below an hour, else hours rounded up. */
    private fun waitText(ms: Long): String {
        val minutes = (ms.coerceAtLeast(0) + 59_999) / 60_000
        return if (minutes < 60) "$minutes min" else "${(minutes + 59) / 60} h"
    }

    private fun clock(elapsedMs: Long): String {
        val seconds = elapsedMs.coerceAtLeast(0) / 1_000
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }
}
