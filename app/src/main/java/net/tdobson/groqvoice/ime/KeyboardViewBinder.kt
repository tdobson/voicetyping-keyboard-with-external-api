package net.tdobson.groqvoice.ime

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import net.tdobson.groqvoice.R
import net.tdobson.groqvoice.core.controller.Status
import net.tdobson.groqvoice.core.controller.StatusFormatter
import net.tdobson.groqvoice.core.gesture.GestureConfig
import net.tdobson.groqvoice.core.gesture.GestureEvent
import net.tdobson.groqvoice.core.gesture.MicGestureDetector

/** What the keyboard view asks the service to do. */
interface KeyboardActions {
    fun onMicGesture(event: GestureEvent)
    fun onBackspace()
    fun onSpace()
    fun onEnter()
    fun onReinsert()
    fun onGlobeTap()
    fun onGlobeLongPress()
    fun onCancelTranscription()
    fun onRetryPending()
    fun onStatusTap(status: Status)
}

/**
 * Wires keyboard.xml to [KeyboardActions] and renders controller state. Main thread only. No text the user
 * dictated or typed ever passes through here: only statuses and counts.
 */
class KeyboardViewBinder(
    val root: View,
    private val actions: KeyboardActions,
    private val gestureConfig: GestureConfig = GestureConfig(),
    private val clock: () -> Long,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val density = root.resources.displayMetrics.density

    private val mic: ImageButton = root.findViewById(R.id.mic_button)
    private val ring: View = root.findViewById(R.id.mic_ring)
    private val progress: View = root.findViewById(R.id.progress)
    private val statusText: TextView = root.findViewById(R.id.status_text)
    private val statusCancel: View = root.findViewById(R.id.status_cancel)
    private val unsentChip: TextView = root.findViewById(R.id.unsent_chip)
    private val backspace: View = root.findViewById(R.id.key_backspace)
    private val enter: TextView = root.findViewById(R.id.key_enter)

    private val detector = MicGestureDetector(gestureConfig, density)
    private var status: Status = Status.Idle
    private var pulse: ValueAnimator? = null

    private val tick = object : Runnable {
        override fun run() {
            renderStatusText()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val repeatBackspace = object : Runnable {
        override fun run() {
            actions.onBackspace()
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    init {
        wireTouchKeys()
        padForNavigationBar()
        root.findViewById<View>(R.id.key_space).setOnClickListener { actions.onSpace() }
        enter.setOnClickListener { actions.onEnter() }
        root.findViewById<View>(R.id.key_reinsert).setOnClickListener { actions.onReinsert() }
        root.findViewById<View>(R.id.key_globe).apply {
            setOnClickListener { actions.onGlobeTap() }
            setOnLongClickListener { actions.onGlobeLongPress(); true }
        }
        statusCancel.setOnClickListener { actions.onCancelTranscription() }
        unsentChip.setOnClickListener { actions.onRetryPending() }
        statusText.setOnClickListener { actions.onStatusTap(status) }
        showStatus(Status.Idle)
    }

    fun showStatus(newStatus: Status) {
        status = newStatus
        syncGesture(newStatus)
        handler.removeCallbacks(tick)
        renderStatusText()
        val recording = newStatus as? Status.Recording
        if (recording != null) handler.postDelayed(tick, TICK_MS)

        progress.visibility = if (StatusFormatter.showsCancel(newStatus)) View.VISIBLE else View.GONE
        statusCancel.visibility = if (StatusFormatter.showsCancel(newStatus)) View.VISIBLE else View.GONE

        val locked = recording?.locked == true
        mic.setImageResource(if (locked) R.drawable.ic_stop else R.drawable.ic_mic)
        mic.contentDescription = root.context.getString(
            if (locked) R.string.key_stop_description else R.string.key_mic_description,
        )
        if (recording != null) startPulse() else stopPulse()
    }

    fun showPendingCount(count: Int) {
        val label = StatusFormatter.unsentChip(count)
        unsentChip.text = label ?: ""
        unsentChip.visibility = if (label != null) View.VISIBLE else View.GONE
    }

    fun setEnterLabel(label: String) {
        enter.text = label
    }

    /** Forget a press in progress, e.g. when the controller refused to start recording. */
    fun resetGesture() = detector.reset()

    /**
     * The controller's status is the truth about recording; the detector only knows what touches it saw. A
     * recording that ended elsewhere (60-minute cap, keyboard hidden) leaves the detector idle so the next press
     * starts afresh; a recording this detector did not start (accessibility click, view recreated mid-recording)
     * is adopted as locked so the visible Stop works.
     */
    private fun syncGesture(s: Status) {
        if (s !is Status.Recording) {
            if (detector.state != MicGestureDetector.State.Idle) detector.reset()
        } else {
            detector.adoptRecording()
        }
    }

    /** The view is going away: stop timers and animations. */
    fun release() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(repeatBackspace)
        stopPulse()
    }

    /**
     * The IME window is edge-to-edge (targetSdk 35+): the system draws its IME navigation bar (hide and switch
     * buttons, gesture handle) over the bottom of our view, so the bottom row must sit above it.
     */
    private fun padForNavigationBar() {
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.tappableElement())
            v.setPadding(left + nav.left, top, right + nav.right, bottom + nav.bottom)
            insets
        }
    }

    /**
     * The mic and backspace are driven by raw touch (hold, slide, auto-repeat), so touches never become clicks.
     * Clicks come only from accessibility services: a click on the mic toggles a hands-free (locked) recording,
     * and a click on backspace deletes once.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun wireTouchKeys() {
        mic.setOnTouchListener(::onMicTouch)
        mic.setOnClickListener { onMicAccessibilityClick() }
        backspace.setOnTouchListener(::onBackspaceTouch)
        backspace.setOnClickListener { actions.onBackspace() }
    }

    private fun onMicAccessibilityClick() {
        if (status is Status.Recording) {
            actions.onMicGesture(GestureEvent.Release)
            return
        }
        actions.onMicGesture(GestureEvent.Hold)
        if (status is Status.Recording) actions.onMicGesture(GestureEvent.Lock)
    }

    private fun renderStatusText() {
        statusText.text = StatusFormatter.text(status, clock())
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onMicTouch(v: View, e: MotionEvent): Boolean {
        val event = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.isPressed = true
                detector.onDown(e.rawX, e.rawY, e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> detector.onMove(e.rawX, e.rawY, e.eventTime)
            MotionEvent.ACTION_UP -> {
                v.isPressed = false
                detector.onUp(e.rawX, e.rawY, e.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                detector.onCancel(e.eventTime)
            }
            else -> null
        }
        event?.let(actions::onMicGesture)
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onBackspaceTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.isPressed = true
                actions.onBackspace()
                handler.removeCallbacks(repeatBackspace)
                handler.postDelayed(repeatBackspace, REPEAT_DELAY_MS)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                handler.removeCallbacks(repeatBackspace)
            }
        }
        return true
    }

    private fun startPulse() {
        ring.visibility = View.VISIBLE
        if (pulse != null) return
        pulse = ValueAnimator.ofFloat(0.85f, 1.1f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val s = it.animatedValue as Float
                ring.scaleX = s
                ring.scaleY = s
                ring.alpha = 1.5f - s
            }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        ring.visibility = View.INVISIBLE
        ring.scaleX = 1f
        ring.scaleY = 1f
        ring.alpha = 1f
    }

    companion object {
        const val TICK_MS = 1_000L
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 50L
    }
}
