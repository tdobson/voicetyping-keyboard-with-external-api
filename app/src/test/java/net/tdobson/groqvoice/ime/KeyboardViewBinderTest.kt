package net.tdobson.groqvoice.ime

import android.content.Context
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import net.tdobson.groqvoice.R
import net.tdobson.groqvoice.core.controller.Status
import net.tdobson.groqvoice.core.gesture.GestureConfig
import net.tdobson.groqvoice.core.gesture.GestureEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class KeyboardViewBinderTest {
    private class RecordingActions : KeyboardActions {
        val log = mutableListOf<String>()
        val gestures = mutableListOf<GestureEvent>()
        val statusTaps = mutableListOf<Status>()
        override fun onMicGesture(event: GestureEvent) { gestures += event }
        override fun onBackspace() { log += "backspace" }
        override fun onSpace() { log += "space" }
        override fun onEnter() { log += "enter" }
        override fun onReinsert() { log += "reinsert" }
        override fun onGlobeTap() { log += "globe" }
        override fun onGlobeLongPress() { log += "globe-long" }
        override fun onCancelTranscription() { log += "cancel" }
        override fun onRetryPending() { log += "retry" }
        override fun onStatusTap(status: Status) { statusTaps += status }
    }

    private lateinit var context: Context
    private lateinit var root: View
    private lateinit var actions: RecordingActions
    private lateinit var binder: KeyboardViewBinder
    private var now = 100_000L
    private var density = 1f

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        density = context.resources.displayMetrics.density
        root = LayoutInflater.from(context).inflate(R.layout.keyboard, null)
        actions = RecordingActions()
        binder = KeyboardViewBinder(root, actions, GestureConfig(), clock = { now })
    }

    private fun <T : View> v(id: Int): T = root.findViewById(id)
    private fun text(id: Int) = v<TextView>(id).text.toString()
    private fun idle(ms: Long) {
        now += ms
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun touch(view: View, action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val e = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        view.dispatchTouchEvent(e)
        e.recycle()
    }

    @Test fun `every stable id exists and keys have content descriptions`() {
        val ids = listOf(
            R.id.mic_button, R.id.mic_ring, R.id.progress, R.id.status_text, R.id.status_cancel, R.id.unsent_chip,
            R.id.key_backspace, R.id.key_space, R.id.key_enter, R.id.key_reinsert, R.id.key_globe,
        )
        ids.forEach { assertNotNull(root.findViewById<View>(it)) }
        listOf(R.id.mic_button, R.id.status_cancel, R.id.unsent_chip, R.id.key_backspace, R.id.key_space,
            R.id.key_enter, R.id.key_reinsert, R.id.key_globe).forEach {
            assertFalse("content description on ${context.resources.getResourceEntryName(it)}",
                v<View>(it).contentDescription.isNullOrBlank())
        }
    }

    @Test fun `idle status shows the hint without progress or cancel`() {
        binder.showStatus(Status.Idle)
        assertEquals("Hold to talk, slide up to lock", text(R.id.status_text))
        assertEquals(View.GONE, v<View>(R.id.progress).visibility)
        assertEquals(View.GONE, v<View>(R.id.status_cancel).visibility)
        assertEquals(View.INVISIBLE, v<View>(R.id.mic_ring).visibility)
    }

    @Test fun `transcribing shows progress and the cancel cross`() {
        binder.showStatus(Status.Transcribing)
        assertEquals("Transcribing...", text(R.id.status_text))
        assertEquals(View.VISIBLE, v<View>(R.id.progress).visibility)
        assertEquals(View.VISIBLE, v<View>(R.id.status_cancel).visibility)
    }

    @Test fun `retrying and waiting show progress and cancel`() {
        binder.showStatus(Status.Retrying(3, 10))
        assertEquals("Retrying (3/10)...", text(R.id.status_text))
        assertEquals(View.VISIBLE, v<View>(R.id.status_cancel).visibility)
        binder.showStatus(Status.WaitingForSignal)
        assertEquals("Waiting for signal...", text(R.id.status_text))
        assertEquals(View.VISIBLE, v<View>(R.id.progress).visibility)
    }

    @Test fun `recording ticks every second and shows the ring`() {
        binder.showStatus(Status.Recording(now, locked = false))
        assertEquals("Recording 0:00", text(R.id.status_text))
        assertEquals(View.VISIBLE, v<View>(R.id.mic_ring).visibility)
        idle(2_000)
        assertEquals("Recording 0:02", text(R.id.status_text))
        binder.showStatus(Status.Transcribing)
        idle(5_000)
        assertEquals("Transcribing...", text(R.id.status_text))
        assertEquals(View.INVISIBLE, v<View>(R.id.mic_ring).visibility)
    }

    @Test fun `locked recording turns the mic into Stop`() {
        binder.showStatus(Status.Recording(now, locked = true))
        assertEquals(context.getString(R.string.key_stop_description), v<View>(R.id.mic_button).contentDescription)
        assertTrue(text(R.id.status_text).endsWith("tap Stop to send"))
        binder.showStatus(Status.Idle)
        assertEquals(context.getString(R.string.key_mic_description), v<View>(R.id.mic_button).contentDescription)
    }

    @Test fun `unsent chip shows the count and hides at zero`() {
        binder.showPendingCount(0)
        assertEquals(View.GONE, v<View>(R.id.unsent_chip).visibility)
        binder.showPendingCount(2)
        assertEquals(View.VISIBLE, v<View>(R.id.unsent_chip).visibility)
        assertEquals("⟳ 2 unsent", text(R.id.unsent_chip))
        binder.showPendingCount(0)
        assertEquals(View.GONE, v<View>(R.id.unsent_chip).visibility)
    }

    @Test fun `enter label follows the resolver label`() {
        binder.setEnterLabel("Send")
        assertEquals("Send", text(R.id.key_enter))
    }

    @Test fun `taps reach the actions`() {
        binder.showPendingCount(1)
        v<View>(R.id.key_space).performClick()
        v<View>(R.id.key_enter).performClick()
        v<View>(R.id.key_reinsert).performClick()
        v<View>(R.id.key_globe).performClick()
        v<View>(R.id.key_globe).performLongClick()
        v<View>(R.id.status_cancel).performClick()
        v<View>(R.id.unsent_chip).performClick()
        assertEquals(listOf("space", "enter", "reinsert", "globe", "globe-long", "cancel", "retry"), actions.log)
    }

    @Test fun `tapping the status passes the current status`() {
        binder.showStatus(Status.NoApiKey)
        v<View>(R.id.status_text).performClick()
        assertEquals(listOf<Status>(Status.NoApiKey), actions.statusTaps)
    }

    @Test fun `short mic tap is Hold then TooShort`() {
        val mic = v<View>(R.id.mic_button)
        touch(mic, MotionEvent.ACTION_DOWN, 40f, 40f, 1_000, 1_000)
        touch(mic, MotionEvent.ACTION_UP, 40f, 40f, 1_000, 1_200)
        assertEquals(listOf(GestureEvent.Hold, GestureEvent.TooShort), actions.gestures)
    }

    @Test fun `mic hold then release is Hold then Release`() {
        val mic = v<View>(R.id.mic_button)
        touch(mic, MotionEvent.ACTION_DOWN, 40f, 40f, 1_000, 1_000)
        touch(mic, MotionEvent.ACTION_UP, 40f, 40f, 1_000, 1_700)
        assertEquals(listOf(GestureEvent.Hold, GestureEvent.Release), actions.gestures)
    }

    @Test fun `slide up locks, then a tap stops`() {
        val mic = v<View>(R.id.mic_button)
        val up = 100 * density
        touch(mic, MotionEvent.ACTION_DOWN, 40f, 40f, 1_000, 1_000)
        touch(mic, MotionEvent.ACTION_MOVE, 40f, 40f - up, 1_000, 1_300)
        touch(mic, MotionEvent.ACTION_UP, 40f, 40f - up, 1_000, 1_400)
        touch(mic, MotionEvent.ACTION_DOWN, 40f, 40f, 5_000, 5_000)
        touch(mic, MotionEvent.ACTION_UP, 40f, 40f, 5_000, 5_080)
        assertEquals(listOf(GestureEvent.Hold, GestureEvent.Lock, GestureEvent.Release), actions.gestures)
    }

    @Test fun `slide left cancels`() {
        val mic = v<View>(R.id.mic_button)
        touch(mic, MotionEvent.ACTION_DOWN, 40f, 40f, 1_000, 1_000)
        touch(mic, MotionEvent.ACTION_MOVE, 40f - 120 * density, 40f, 1_000, 1_500)
        touch(mic, MotionEvent.ACTION_UP, 40f - 120 * density, 40f, 1_000, 1_600)
        assertEquals(listOf(GestureEvent.Hold, GestureEvent.Cancel), actions.gestures)
    }

    @Test fun `system cancel while held counts as release`() {
        val mic = v<View>(R.id.mic_button)
        touch(mic, MotionEvent.ACTION_DOWN, 40f, 40f, 1_000, 1_000)
        touch(mic, MotionEvent.ACTION_CANCEL, 40f, 40f, 1_000, 2_000)
        assertEquals(listOf(GestureEvent.Hold, GestureEvent.Release), actions.gestures)
    }

    @Test fun `resetGesture after the controller refuses lets the next press start again`() {
        val mic = v<View>(R.id.mic_button)
        touch(mic, MotionEvent.ACTION_DOWN, 40f, 40f, 1_000, 1_000)
        binder.resetGesture()
        touch(mic, MotionEvent.ACTION_UP, 40f, 40f, 1_000, 1_100)
        touch(mic, MotionEvent.ACTION_DOWN, 40f, 40f, 2_000, 2_000)
        assertEquals(listOf(GestureEvent.Hold, GestureEvent.Hold), actions.gestures)
    }

    @Test fun `backspace deletes once on press and repeats after 400 ms every 50 ms until release`() {
        val bs = v<View>(R.id.key_backspace)
        touch(bs, MotionEvent.ACTION_DOWN, 10f, 10f, 0, 0)
        assertEquals(1, actions.log.count { it == "backspace" })
        idle(399)
        assertEquals(1, actions.log.count { it == "backspace" })
        idle(1)
        assertEquals(2, actions.log.count { it == "backspace" })
        idle(150)
        assertEquals(5, actions.log.count { it == "backspace" })
        touch(bs, MotionEvent.ACTION_UP, 10f, 10f, 0, 550)
        idle(1_000)
        assertEquals(5, actions.log.count { it == "backspace" })
    }

    @Test fun `accessibility click starts a locked recording, a second click stops it`() {
        val mic = v<View>(R.id.mic_button)
        mic.performClick()
        assertEquals(listOf(GestureEvent.Hold), actions.gestures)
        // The service answers Hold by recording; then the click locks it hands-free.
        actions.gestures.clear()
        binder.showStatus(Status.Recording(now, locked = false))
        actions.gestures.clear()
        binder = KeyboardViewBinder(root, object : KeyboardActions by actions {
            override fun onMicGesture(event: GestureEvent) {
                actions.onMicGesture(event)
                if (event == GestureEvent.Hold) binder.showStatus(Status.Recording(now, locked = false))
                if (event == GestureEvent.Lock) binder.showStatus(Status.Recording(now, locked = true))
            }
        }, GestureConfig(), clock = { now })
        mic.performClick()
        assertEquals(listOf(GestureEvent.Hold, GestureEvent.Lock), actions.gestures)
        mic.performClick()
        assertEquals(listOf(GestureEvent.Hold, GestureEvent.Lock, GestureEvent.Release), actions.gestures)
    }

    /** Answers gestures the way the service and controller do, so the binder sees the real status sequence. */
    private fun echoingBinder(on: View = root): KeyboardViewBinder {
        lateinit var b: KeyboardViewBinder
        b = KeyboardViewBinder(on, object : KeyboardActions by actions {
            override fun onMicGesture(event: GestureEvent) {
                actions.onMicGesture(event)
                when (event) {
                    GestureEvent.Hold -> b.showStatus(Status.Recording(now, locked = false))
                    GestureEvent.Lock -> b.showStatus(Status.Recording(now, locked = true))
                    GestureEvent.Release -> b.showStatus(Status.Transcribing)
                    GestureEvent.Cancel -> b.showStatus(Status.Idle)
                    GestureEvent.TooShort -> b.showStatus(Status.HoldToTalk)
                }
            }
        }, GestureConfig(), clock = { now })
        return b
    }

    private fun lockByTouch(mic: View) {
        val up = 100 * density
        touch(mic, MotionEvent.ACTION_DOWN, 40f, 40f, 1_000, 1_000)
        touch(mic, MotionEvent.ACTION_MOVE, 40f, 40f - up, 1_000, 1_300)
        touch(mic, MotionEvent.ACTION_UP, 40f, 40f - up, 1_000, 1_400)
    }

    @Test fun `after the max-duration stop of a locked recording the next press starts a new recording`() {
        binder = echoingBinder()
        val mic = v<View>(R.id.mic_button)
        lockByTouch(mic)
        // The 60-minute cap stops the recording without any touch.
        binder.showStatus(Status.Transcribing)
        actions.gestures.clear()
        touch(mic, MotionEvent.ACTION_DOWN, 40f, 40f, 9_000, 9_000)
        assertEquals(listOf(GestureEvent.Hold), actions.gestures)
    }

    @Test fun `a view recreated while a locked recording runs can stop it with a tap`() {
        val fresh = LayoutInflater.from(context).inflate(R.layout.keyboard, null)
        val b = KeyboardViewBinder(fresh, actions, GestureConfig(), clock = { now })
        b.showStatus(Status.Recording(now - 5_000, locked = true))
        val mic = fresh.findViewById<View>(R.id.mic_button)
        touch(mic, MotionEvent.ACTION_DOWN, 40f, 40f, 9_000, 9_000)
        touch(mic, MotionEvent.ACTION_UP, 40f, 40f, 9_000, 9_080)
        assertEquals(listOf(GestureEvent.Release), actions.gestures)
    }

    @Test fun `a recording started by an accessibility click can be stopped by touching Stop`() {
        binder = echoingBinder()
        val mic = v<View>(R.id.mic_button)
        mic.performClick()
        assertEquals(listOf(GestureEvent.Hold, GestureEvent.Lock), actions.gestures)
        touch(mic, MotionEvent.ACTION_DOWN, 40f, 40f, 9_000, 9_000)
        touch(mic, MotionEvent.ACTION_UP, 40f, 40f, 9_000, 9_080)
        assertEquals(listOf(GestureEvent.Hold, GestureEvent.Lock, GestureEvent.Release), actions.gestures)
    }

    @Test fun `a status that ends the recording mid-hold makes the lift do nothing`() {
        binder = echoingBinder()
        val mic = v<View>(R.id.mic_button)
        touch(mic, MotionEvent.ACTION_DOWN, 40f, 40f, 1_000, 1_000)
        binder.showStatus(Status.Transcribing)
        touch(mic, MotionEvent.ACTION_UP, 40f, 40f, 1_000, 1_900)
        assertEquals(listOf(GestureEvent.Hold), actions.gestures)
    }

    @Test fun `accessibility click when the recording is refused does not lock`() {
        v<View>(R.id.mic_button).performClick()
        assertEquals(listOf(GestureEvent.Hold), actions.gestures)
    }

    @Test fun `bottom padding grows by the navigation bar inset so system buttons do not cover the keys`() {
        val base = root.paddingBottom
        val insets = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, 63))
            .build()
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root, insets)
        assertEquals(base + 63, root.paddingBottom)
        // Applying again (e.g. rotation) does not accumulate.
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root, insets)
        assertEquals(base + 63, root.paddingBottom)
    }

    @Test fun `release stops the recording ticker`() {
        binder.showStatus(Status.Recording(now, locked = false))
        binder.release()
        idle(3_000)
        assertEquals("Recording 0:00", text(R.id.status_text))
    }
}
