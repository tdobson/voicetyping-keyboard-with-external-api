package net.tdobson.groqvoice.ime

import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import net.tdobson.groqvoice.core.controller.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The effect-to-platform mapping (tones and haptic constants). Constants are compile-time ints from android.jar. */
class FeedbackTest {
    @Test fun `tones match the plan, retry is the loud one`() {
        assertEquals(ToneGenerator.TONE_PROP_BEEP, Feedback.toneFor(Effect.StartTone)?.first)
        assertEquals(ToneGenerator.TONE_PROP_BEEP2, Feedback.toneFor(Effect.StopTone)?.first)
        assertEquals(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, Feedback.toneFor(Effect.RetryTone)?.first)
        assertEquals(ToneGenerator.TONE_SUP_ERROR, Feedback.toneFor(Effect.ErrorTone)?.first)
        assertNull(Feedback.toneFor(Effect.HapticConfirm))
    }

    @Test fun `haptics use CONFIRM and REJECT on API 30+ with fallbacks below`() {
        assertEquals(HapticFeedbackConstants.CONFIRM, Feedback.hapticFor(Effect.HapticConfirm, 30))
        assertEquals(HapticFeedbackConstants.REJECT, Feedback.hapticFor(Effect.HapticReject, 34))
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, Feedback.hapticFor(Effect.HapticConfirm, 29))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, Feedback.hapticFor(Effect.HapticReject, 29))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, Feedback.hapticFor(Effect.HapticRetry, 35))
        assertNull(Feedback.hapticFor(Effect.StartTone, 35))
    }
}
