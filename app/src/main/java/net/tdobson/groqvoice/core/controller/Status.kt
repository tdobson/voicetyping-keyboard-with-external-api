package net.tdobson.groqvoice.core.controller

/** What the keyboard's status line shows. */
sealed interface Status {
    data object Idle : Status
    data class Recording(val startedAtMs: Long, val locked: Boolean) : Status
    data object Transcribing : Status
    data class Retrying(val attempt: Int, val maxAttempts: Int) : Status
    data object WaitingForSignal : Status
    /** The field changed during transcription: the text is on the clipboard and ↺ inserts it. */
    data object TextReady : Status
    data object NothingHeard : Status
    data object HoldToTalk : Status
    data object NoApiKey : Status
    data object NoMicPermission : Status
    data object AuthRejected : Status
    data class Rejected(val message: String) : Status
    data class GaveUp(val attempts: Int) : Status
    data object Cancelled : Status
    data object RecordingFailed : Status
}

/** A moment that deserves sound and/or haptic feedback. */
enum class Cue { RecordStart, RecordStop, Retry, Error, NothingHeard }
