package net.tdobson.groqvoice.core.controller

import net.tdobson.groqvoice.core.controller.Effect.ErrorTone
import net.tdobson.groqvoice.core.controller.Effect.HapticConfirm
import net.tdobson.groqvoice.core.controller.Effect.HapticReject
import net.tdobson.groqvoice.core.controller.Effect.HapticRetry
import net.tdobson.groqvoice.core.controller.Effect.RetryTone
import net.tdobson.groqvoice.core.controller.Effect.StartTone
import net.tdobson.groqvoice.core.controller.Effect.StopTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackPolicyTest {
    private fun effects(cue: Cue, sound: Boolean = true, haptic: Boolean = true) = FeedbackPolicy.effects(cue, sound, haptic)

    @Test fun `record start`() = assertEquals(setOf(StartTone, HapticConfirm), effects(Cue.RecordStart))
    @Test fun `record stop`() = assertEquals(setOf(StopTone, HapticConfirm), effects(Cue.RecordStop))
    @Test fun `retry`() = assertEquals(setOf(RetryTone, HapticRetry), effects(Cue.Retry))
    @Test fun `error`() = assertEquals(setOf(ErrorTone, HapticReject), effects(Cue.Error))
    @Test fun `nothing heard is a short haptic only`() = assertEquals(setOf(HapticReject), effects(Cue.NothingHeard))

    @Test fun `sound off removes every tone`() {
        assertEquals(setOf(HapticConfirm), effects(Cue.RecordStart, sound = false))
        assertEquals(setOf(HapticRetry), effects(Cue.Retry, sound = false))
        assertEquals(setOf(HapticReject), effects(Cue.Error, sound = false))
    }

    @Test fun `haptic off removes every haptic`() {
        assertEquals(setOf(StopTone), effects(Cue.RecordStop, haptic = false))
        assertEquals(setOf(ErrorTone), effects(Cue.Error, haptic = false))
        assertEquals(emptySet<Effect>(), effects(Cue.NothingHeard, haptic = false))
    }

    @Test fun `both off is silent for every cue`() {
        for (cue in Cue.entries) assertTrue("$cue", effects(cue, sound = false, haptic = false).isEmpty())
    }
}
