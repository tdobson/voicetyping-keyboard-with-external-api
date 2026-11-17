package net.tdobson.groqvoice.core.controller

enum class Effect { StartTone, StopTone, RetryTone, ErrorTone, HapticConfirm, HapticRetry, HapticReject }

/** Which tones and haptics each cue gets, after the user's sound and haptic toggles. */
object FeedbackPolicy {
    private val TONES = setOf(Effect.StartTone, Effect.StopTone, Effect.RetryTone, Effect.ErrorTone)

    fun effects(cue: Cue, soundOn: Boolean, hapticOn: Boolean): Set<Effect> {
        val all = when (cue) {
            Cue.RecordStart -> setOf(Effect.StartTone, Effect.HapticConfirm)
            Cue.RecordStop -> setOf(Effect.StopTone, Effect.HapticConfirm)
            Cue.Retry -> setOf(Effect.RetryTone, Effect.HapticRetry)
            Cue.Error -> setOf(Effect.ErrorTone, Effect.HapticReject)
            Cue.NothingHeard -> setOf(Effect.HapticReject)
        }
        return all.filterTo(mutableSetOf()) { if (it in TONES) soundOn else hapticOn }
    }
}
