package net.tdobson.groqvoice.core.gesture

import kotlin.math.abs

/** Every mic gesture threshold, in one place. Distances are in dp and converted with the screen density. */
data class GestureConfig(
    val holdMs: Long = 500,
    val lockSlideDp: Float = 70f,
    val cancelSlideDp: Float = 90f,
) {
    fun lockSlidePx(density: Float): Float = lockSlideDp * density
    fun cancelSlidePx(density: Float): Float = cancelSlideDp * density
}

enum class GestureEvent {
    /** Finger down: start recording. */
    Hold,
    /** Slid up: keep recording hands-free until the Stop tap. */
    Lock,
    /** Slid left: discard the recording. */
    Cancel,
    /** Lifted after the hold time, or the Stop tap while locked: transcribe. */
    Release,
    /** Lifted before the hold time: discard and hint "Hold to talk". */
    TooShort,
}

/**
 * Messenger-style mic gestures as a pure state machine fed with coordinates (px) and times (ms).
 * dx < 0 is left, dy < 0 is up. On a diagonal the dominant axis wins: only cancel is checked when |dx| > |dy|,
 * otherwise only lock. A system ACTION_CANCEL while pressed counts as lifting the finger.
 */
class MicGestureDetector(private val config: GestureConfig = GestureConfig(), private val density: Float) {
    enum class State { Idle, Pressed, Locked }

    var state: State = State.Idle
        private set

    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    /** While locked: a fresh finger-down has started the Stop tap. */
    private var stopTapDown = false

    fun onDown(x: Float, y: Float, timeMs: Long): GestureEvent? = when (state) {
        State.Idle -> {
            downX = x
            downY = y
            downTimeMs = timeMs
            state = State.Pressed
            GestureEvent.Hold
        }
        State.Pressed -> null
        State.Locked -> {
            stopTapDown = true
            null
        }
    }

    fun onMove(x: Float, y: Float, timeMs: Long): GestureEvent? {
        if (state != State.Pressed) return null
        val dx = x - downX
        val dy = y - downY
        return if (abs(dx) > abs(dy)) {
            if (dx <= -config.cancelSlidePx(density)) finish(GestureEvent.Cancel) else null
        } else {
            if (dy <= -config.lockSlidePx(density)) {
                state = State.Locked
                stopTapDown = false
                GestureEvent.Lock
            } else {
                null
            }
        }
    }

    fun onUp(x: Float, y: Float, timeMs: Long): GestureEvent? = when (state) {
        State.Idle -> null
        State.Pressed -> finish(liftResult(timeMs))
        State.Locked -> if (stopTapDown) finish(GestureEvent.Release) else null
    }

    fun onCancel(timeMs: Long): GestureEvent? = when (state) {
        State.Idle -> null
        State.Pressed -> finish(liftResult(timeMs))
        State.Locked -> {
            stopTapDown = false
            null
        }
    }

    /** The recording ended without this detector (max duration, keyboard hidden, refused): forget any press. */
    fun reset() {
        state = State.Idle
        stopTapDown = false
    }

    /**
     * A recording is running that this detector did not start (an accessibility click, or a view recreated
     * mid-recording): treat it as locked, so the next tap is the Stop tap. No-op unless idle.
     */
    fun adoptRecording() {
        if (state != State.Idle) return
        state = State.Locked
        stopTapDown = false
    }

    private fun liftResult(timeMs: Long) =
        if (timeMs - downTimeMs >= config.holdMs) GestureEvent.Release else GestureEvent.TooShort

    private fun finish(event: GestureEvent): GestureEvent {
        state = State.Idle
        stopTapDown = false
        return event
    }
}
