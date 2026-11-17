package net.tdobson.groqvoice.ime

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import net.tdobson.groqvoice.core.controller.Effect
import net.tdobson.groqvoice.util.Logx

/** Plays the tones and haptics chosen by FeedbackPolicy (which already applied the user's toggles). */
class Feedback(context: Context) {
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)
    private var tones: ToneGenerator? = null

    fun play(effects: Set<Effect>, view: View?) {
        effects.forEach { effect ->
            toneFor(effect)?.let { (tone, ms) -> tone(tone, ms) }
            hapticFor(effect, Build.VERSION.SDK_INT)?.let { haptic(it, effect, view) }
        }
    }

    /** A short click for ordinary keys. */
    fun keyTap(view: View?) {
        view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun release() {
        tones?.release()
        tones = null
    }

    private fun tone(tone: Int, ms: Int) {
        try {
            val gen = tones ?: ToneGenerator(AudioManager.STREAM_SYSTEM, VOLUME).also { tones = it }
            gen.startTone(tone, ms)
        } catch (e: RuntimeException) {
            Logx.w(e) { "tone unavailable" }
        }
    }

    private fun haptic(constant: Int, effect: Effect, view: View?) {
        if (view != null && view.isAttachedToWindow && view.isShown) {
            view.performHapticFeedback(constant)
            return
        }
        // Keyboard hidden (e.g. an error arriving later): vibrate directly.
        val predefined = when (effect) {
            Effect.HapticConfirm -> VibrationEffect.EFFECT_CLICK
            Effect.HapticRetry -> VibrationEffect.EFFECT_DOUBLE_CLICK
            else -> VibrationEffect.EFFECT_HEAVY_CLICK
        }
        try {
            vibrator?.vibrate(VibrationEffect.createPredefined(predefined))
        } catch (e: RuntimeException) {
            Logx.w(e) { "vibrate failed" }
        }
    }

    companion object {
        private const val VOLUME = 80

        /** Tone id and duration (ms), or null if [effect] is not a sound. */
        fun toneFor(effect: Effect): Pair<Int, Int>? = when (effect) {
            Effect.StartTone -> ToneGenerator.TONE_PROP_BEEP to 120
            Effect.StopTone -> ToneGenerator.TONE_PROP_BEEP2 to 150
            Effect.RetryTone -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD to 400
            Effect.ErrorTone -> ToneGenerator.TONE_SUP_ERROR to 350
            else -> null
        }

        /** HapticFeedbackConstants for [effect] on [sdk], or null if it is not a haptic. */
        @SuppressLint("InlinedApi") // CONFIRM and REJECT are only returned when sdk >= 30
        fun hapticFor(effect: Effect, sdk: Int): Int? = when (effect) {
            Effect.HapticConfirm ->
                if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP
            Effect.HapticReject ->
                if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            Effect.HapticRetry -> HapticFeedbackConstants.LONG_PRESS
            else -> null
        }
    }
}
