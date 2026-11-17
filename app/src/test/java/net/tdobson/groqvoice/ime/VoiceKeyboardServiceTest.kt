package net.tdobson.groqvoice.ime

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import net.tdobson.groqvoice.R
import net.tdobson.groqvoice.storage.AppStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Layer 3: the service's wiring that does not need a real InputConnection, Keystore or microphone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceKeyboardServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun editor(imeOptions: Int, inputType: Int = InputType.TYPE_CLASS_TEXT) = EditorInfo().apply {
        this.imeOptions = imeOptions
        this.inputType = inputType
        packageName = "com.example.chat"
        fieldId = 42
    }

    private fun startService(info: EditorInfo): Pair<VoiceKeyboardService, View> {
        val service = Robolectric.buildService(VoiceKeyboardService::class.java).create().get()
        val view = service.onCreateInputView()
        service.onStartInput(info, false)
        service.onStartInputView(info, false)
        shadowOf(Looper.getMainLooper()).idle()
        return service to view
    }

    private fun text(view: View, id: Int) = view.findViewById<TextView>(id).text.toString()

    @Test fun `enter shows the field's action`() {
        val (_, view) = startService(editor(EditorInfo.IME_ACTION_SEND))
        assertEquals("Send", text(view, R.id.key_enter))
    }

    @Test fun `enter is a newline in a multi-line field without an action`() {
        val (_, view) = startService(
            editor(EditorInfo.IME_ACTION_UNSPECIFIED, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE),
        )
        assertEquals("⏎", text(view, R.id.key_enter))
    }

    @Test fun `mic press without an API key is refused with a hint and records nothing`() {
        val (_, view) = startService(editor(EditorInfo.IME_ACTION_DONE))
        val mic = view.findViewById<View>(R.id.mic_button)
        val t = SystemClock.uptimeMillis()
        mic.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        mic.dispatchTouchEvent(MotionEvent.obtain(t, t + 900, MotionEvent.ACTION_UP, 10f, 10f, 0))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Add your Groq API key in Groq Voice settings", text(view, R.id.status_text))
        val recording = File(AppStorage.voiceDir(context), "recording")
        assertTrue(recording.listFiles().isNullOrEmpty())
    }

    @Test fun `an interrupted recording is recovered as unsent when the service starts`() {
        val dir = File(AppStorage.voiceDir(context), "recording").apply { mkdirs() }
        File(dir, "1700000000000.ogg").writeBytes(ByteArray(4096) { 1 })
        val (_, view) = startService(editor(EditorInfo.IME_ACTION_DONE))
        val chip = view.findViewById<TextView>(R.id.unsent_chip)
        assertEquals(View.VISIBLE, chip.visibility)
        assertEquals("⟳ 1 unsent", chip.text.toString())
        assertFalse(File(dir, "1700000000000.ogg").exists())
    }

    @Test fun `idle hint is shown on a fresh keyboard`() {
        val (_, view) = startService(editor(EditorInfo.IME_ACTION_DONE))
        assertEquals("Hold to talk, slide up to lock", text(view, R.id.status_text))
        assertEquals(View.GONE, view.findViewById<View>(R.id.unsent_chip).visibility)
    }
}
