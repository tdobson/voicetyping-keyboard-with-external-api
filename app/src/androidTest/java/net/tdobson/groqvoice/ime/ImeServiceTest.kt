package net.tdobson.groqvoice.ime

import android.content.ComponentName
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import net.tdobson.groqvoice.EmulatorOnly
import net.tdobson.groqvoice.R
import net.tdobson.groqvoice.security.ApiKeyStore
import net.tdobson.groqvoice.settings.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/** Layer 4: the real IME on the emulator, typing into the settings screen's "Try it here" field. */
@RunWith(AndroidJUnit4::class)
class ImeServiceTest {
    private val pkg = "net.tdobson.groqvoice"
    private val ime = "$pkg/.ime.VoiceKeyboardService"
    private lateinit var context: Context
    private lateinit var device: UiDevice
    private var previousIme: String? = null
    private var addedFakeKey = false
    private var scenario: ActivityScenario<SettingsActivity>? = null

    @Before fun setUp() {
        assumeTrue("instrumented tests run on the emulator only", EmulatorOnly.isEmulator())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        device = UiDevice.getInstance(instrumentation)
        previousIme = shell("settings get secure default_input_method").trim().takeIf { it.isNotEmpty() && it != "null" }
        shell("ime enable $ime")
        shell("ime set $ime")
        // Recording needs a key (any key: nothing is sent in this test). Never overwrite a real one.
        val keys = ApiKeyStore.from(context)
        if (!keys.hasKey()) {
            keys.save("test-key-123")
            addedFakeKey = true
        }
    }

    @After fun tearDown() {
        scenario?.close()
        if (addedFakeKey) ApiKeyStore.from(context).clear()
        previousIme?.let { shell("ime set $it") }
    }

    private fun shell(cmd: String): String {
        val pfd: ParcelFileDescriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { FileInputStream(pfd.fileDescriptor).bufferedReader().readText() }
    }

    private fun key(id: String): UiObject2 {
        val found = device.wait(Until.findObject(By.res(pkg, id)), 10_000)
        assertNotNull("keyboard view $id", found)
        return found
    }

    private fun fieldText(): String {
        var text = ""
        scenario!!.onActivity { text = it.findViewById<EditText>(R.id.try_it_here).text.toString() }
        return text
    }

    private fun currentIme() = shell("settings get secure default_input_method").trim().replace("$pkg/$pkg.", "$pkg/.")

    private fun openKeyboardOnTryItHere() {
        // Right after a reinstall the system can take a moment to accept `ime set`.
        assertTrue("our IME is selected", device.wait({ currentIme() == ime || run { shell("ime set $ime"); false } }, 10_000))
        scenario = ActivityScenario.launch(SettingsActivity::class.java)
        scenario!!.onActivity { it.findViewById<EditText>(R.id.try_it_here).requestFocus() }
        device.waitForIdle()
        val field = device.wait(Until.findObject(By.res(pkg, "try_it_here")), 10_000)
        assertNotNull("try_it_here on screen", field)
        field.click()
        // Showing the keyboard on a tap is the system's job; if the tap raced the window gaining focus, ask once more.
        if (!device.wait(Until.hasObject(By.res(pkg, "mic_button")), 5_000)) {
            scenario!!.onActivity {
                val f = it.findViewById<EditText>(R.id.try_it_here)
                f.requestFocus()
                it.getSystemService(InputMethodManager::class.java).showSoftInput(f, 0)
            }
        }
        assertTrue("our keyboard appears", device.wait(Until.hasObject(By.res(pkg, "mic_button")), 10_000))
    }

    @Test fun keyboardIsInstalledAndSelectable() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        val ours = ComponentName(pkg, "$pkg.ime.VoiceKeyboardService")
        assertTrue(imm.inputMethodList.any { it.component == ours })
        assertTrue(imm.enabledInputMethodList.any { it.component == ours })
        assertEquals(ime, currentIme())
    }

    @Test fun keysTypeIntoTheField() {
        openKeyboardOnTryItHere()
        key("key_space").click()
        key("key_space").click()
        device.waitForIdle()
        assertTrue(device.wait({ fieldText() == "  " }, 5_000))
        key("key_backspace").click()
        assertTrue("one backspace leaves one space, was [${fieldText()}]", device.wait({ fieldText() == " " }, 5_000))
    }

    @Test fun enterInAMultiLineFieldIsANewline() {
        openKeyboardOnTryItHere()
        assertEquals("⏎", key("key_enter").text)
        key("key_enter").click()
        assertTrue("field after enter was [${fieldText().replace("\n", "\\n")}]", device.wait({ fieldText() == "\n" }, 5_000))
    }

    @Test fun shortMicPressHintsHoldToTalk() {
        openKeyboardOnTryItHere()
        pressWithEventTimes(key("mic_button"), holdMs = 300)
        assertTrue(
            "status shows Hold to talk",
            device.wait(Until.hasObject(By.res(pkg, "status_text").text("Hold to talk")), 5_000),
        )
        assertEquals("", fieldText())
    }

    @Test fun idleHintAndNoUnsentChipOnAFreshKeyboard() {
        openKeyboardOnTryItHere()
        assertEquals("Hold to talk, slide up to lock", key("status_text").text)
    }

    /**
     * A press whose DOWN and UP carry event times [holdMs] apart, whatever the emulator's speed. The gesture
     * detector uses event times, so this is a short press by construction and never sends audio anywhere.
     */
    private fun pressWithEventTimes(target: UiObject2, holdMs: Long) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val c = target.visibleCenter
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, time: Long) {
            val e = MotionEvent.obtain(down, time, action, c.x.toFloat(), c.y.toFloat(), 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(e, true))
            e.recycle()
        }
        inject(MotionEvent.ACTION_DOWN, down)
        SystemClock.sleep(holdMs)
        inject(MotionEvent.ACTION_UP, down + holdMs)
    }

    private fun UiDevice.wait(condition: () -> Boolean, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }
}
