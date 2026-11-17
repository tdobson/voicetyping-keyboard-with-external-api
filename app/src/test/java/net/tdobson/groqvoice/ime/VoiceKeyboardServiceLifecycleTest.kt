package net.tdobson.groqvoice.ime

import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import net.tdobson.groqvoice.R
import net.tdobson.groqvoice.core.controller.ConnectivityGate
import net.tdobson.groqvoice.core.controller.FakeRecorder
import net.tdobson.groqvoice.core.controller.FieldIdentity
import net.tdobson.groqvoice.core.controller.MicPermission
import net.tdobson.groqvoice.core.net.TranscriptionApi
import net.tdobson.groqvoice.core.net.TranscriptionRequest
import net.tdobson.groqvoice.core.net.TranscriptionResult
import net.tdobson.groqvoice.storage.AppStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Layer 3: the service's lifecycle decisions (never lose speech, which field text goes to), with the
 * microphone, Keystore and network replaced by fakes through [VoiceKeyboardService.createPorts].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceKeyboardServiceLifecycleTest {
    /** Answers each transcription with [answer], which the test completes when it wants. */
    class ScriptedApi : TranscriptionApi {
        val calls = mutableListOf<File>()
        var answer = CompletableDeferred<TranscriptionResult>()
        override suspend fun transcribe(audio: File, apiKey: String, request: TranscriptionRequest): TranscriptionResult {
            calls += audio
            return answer.await()
        }
    }

    class FakePortsService : VoiceKeyboardService() {
        override fun createPorts() = Ports(
            api = api,
            gate = object : ConnectivityGate { override suspend fun awaitOnline() = Unit },
            recorder = recorder,
            keys = { "test-key-lifecycle" },
            mic = MicPermission { true },
        )

        companion object {
            lateinit var api: ScriptedApi
            lateinit var recorder: FakeRecorder
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var service: VoiceKeyboardService
    private lateinit var view: View
    private val api = ScriptedApi()
    private val recorder = FakeRecorder()
    private val info = editor(42)

    private fun editor(fieldId: Int) = EditorInfo().apply {
        imeOptions = EditorInfo.IME_ACTION_SEND
        inputType = InputType.TYPE_CLASS_TEXT
        packageName = "com.example.chat"
        this.fieldId = fieldId
    }

    @Before fun setUp() {
        FakePortsService.api = api
        FakePortsService.recorder = recorder
        service = Robolectric.buildService(FakePortsService::class.java).create().get()
        view = service.onCreateInputView()
        service.onStartInput(info, false)
        service.onStartInputView(info, false)
        idle()
    }

    @After fun tearDown() {
        api.answer.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun dir(name: String) = File(AppStorage.voiceDir(context), name)
    private fun files(name: String) = dir(name).listFiles().orEmpty().filter { it.isFile }
    private fun status() = view.findViewById<TextView>(R.id.status_text).text.toString()

    private fun tap(mic: View, downAt: Long, upAfterMs: Long, dy: Float = 0f) {
        val t = SystemClock.uptimeMillis() + downAt
        mic.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        if (dy != 0f) mic.dispatchTouchEvent(MotionEvent.obtain(t, t + 100, MotionEvent.ACTION_MOVE, 10f, 10f + dy, 0))
        mic.dispatchTouchEvent(MotionEvent.obtain(t, t + upAfterMs, MotionEvent.ACTION_UP, 10f, 10f + dy, 0))
        idle()
    }

    private fun mic(v: View = view) = v.findViewById<View>(R.id.mic_button)

    /** Hold and slide up: a hands-free recording. */
    private fun startLockedRecording() {
        val up = -200 * context.resources.displayMetrics.density
        tap(mic(), downAt = 0, upAfterMs = 200, dy = up)
        assertEquals(1, recorder.started)
        assertTrue(status().endsWith("tap Stop to send"))
    }

    @Test fun `hiding the keyboard while recording keeps the audio pending and sends it`() {
        startLockedRecording()
        service.onFinishInputView(false)
        idle()
        assertEquals(1, recorder.stopped)
        assertEquals(1, files("pending").size)
        assertTrue(files("recording").isEmpty())
        assertEquals(listOf(files("pending").single().name), api.calls.map { it.name })
    }

    @Test fun `text from a recording stopped by hiding the keyboard goes to the clipboard`() {
        startLockedRecording()
        service.onFinishInputView(false) // a plain hide: the field keeps focus, no onFinishInput
        api.answer.complete(TranscriptionResult.Success(" Hello from the hidden keyboard."))
        idle()
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("Hello from the hidden keyboard.", clip.getItemAt(0).text.toString())
        assertEquals("Text ready, tap ↺ to insert", status())
        assertTrue(files("pending").isEmpty())
    }

    @Test fun `destroying the service while recording leaves the audio in pending`() {
        startLockedRecording()
        service.onDestroy()
        idle()
        assertEquals(1, files("pending").size)
        assertTrue(files("recording").isEmpty())
    }

    @Test fun `field identity follows onStartInput and onFinishInput`() {
        val first = service.currentField()
        assertEquals("com.example.chat", first?.packageName)
        assertEquals(42, first?.fieldId)
        service.onFinishInput()
        assertNull(service.currentField())
        service.onStartInput(info, false)
        val second = service.currentField()!!
        assertEquals(first!!.copy(session = second.session), second)
        assertNotEquals(first.session, second.session)
    }

    @Test fun `a restart of the same field counts as a new field, so text in flight goes to the clipboard`() {
        // Deliberate (fails safe): an app may reuse one EditText for another conversation and call restartInput.
        val before = service.currentField()!!
        service.onStartInput(info, true)
        assertNotEquals(before, service.currentField())
    }

    @Test fun `after the max-duration stop of a locked recording the next press records again`() {
        startLockedRecording()
        recorder.hitMaxDuration()
        idle()
        assertEquals(1, recorder.stopped)
        assertEquals("Transcribing...", status())
        val t = SystemClock.uptimeMillis()
        mic().dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        idle()
        assertEquals(2, recorder.started)
        assertTrue(status().startsWith("Recording "))
    }

    @Test fun `a view recreated while a locked recording runs can stop it with Stop`() {
        startLockedRecording()
        view = service.onCreateInputView() // e.g. rotation
        assertTrue(status().endsWith("tap Stop to send"))
        tap(mic(), downAt = 5_000, upAfterMs = 80)
        assertEquals(1, recorder.stopped)
        assertEquals(1, files("pending").size)
        assertEquals(1, api.calls.size)
    }

    @Test fun `the field captured at stop time is the one text is committed for`() {
        startLockedRecording()
        val atStop: FieldIdentity = service.currentField()!!
        tap(mic(), downAt = 5_000, upAfterMs = 80)
        service.onStartInput(editor(43), false)
        api.answer.complete(TranscriptionResult.Success("Moved on."))
        idle()
        assertNotEquals(atStop, service.currentField())
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("Moved on.", clip.getItemAt(0).text.toString())
    }
}
