package net.tdobson.groqvoice.settings

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ApplicationProvider
import net.tdobson.groqvoice.R
import net.tdobson.groqvoice.core.net.KeyCheckResult
import net.tdobson.groqvoice.security.KeyStorage
import net.tdobson.groqvoice.storage.AppStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsActivityTest {
    private class FakeKeyStorage(var stored: String? = null) : KeyStorage {
        val saves = mutableListOf<String>()
        override fun save(apiKey: String) { saves += apiKey; stored = apiKey }
        override fun apiKey(): String? = stored
        override fun clear() { stored = null }
        override fun hasKey(): Boolean = stored != null
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var keys: FakeKeyStorage
    private val checked = mutableListOf<String>()
    private var checkResult: KeyCheckResult = KeyCheckResult.Valid
    private lateinit var controller: ActivityController<SettingsActivity>

    @Before fun setUp() {
        keys = FakeKeyStorage()
        SettingsActivity.keyStoreFactory = { keys }
        SettingsActivity.keyChecker = { key -> checked += key; checkResult }
    }

    @After fun tearDown() {
        SettingsActivity.resetSeams()
    }

    private fun launch(): SettingsActivity {
        controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        return controller.get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun SettingsActivity.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test fun `all stable ids exist`() {
        val a = launch()
        listOf(
            R.id.api_key_input, R.id.save_key_button, R.id.test_key_button, R.id.test_key_result,
            R.id.mic_permission_button, R.id.mic_permission_status, R.id.enable_keyboard_button,
            R.id.choose_keyboard_button, R.id.model_spinner, R.id.language_input, R.id.sound_switch,
            R.id.haptic_switch, R.id.unsent_status, R.id.delete_unsent_button, R.id.try_it_here,
        ).forEach { assertNotNull(context.resources.getResourceEntryName(it), a.findViewById<View>(it)) }
    }

    @Test fun `api key field is masked`() {
        val input = launch().findViewById<EditText>(R.id.api_key_input)
        assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            input.inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION))
    }

    @Test fun `saving a blank key shows an error and does not touch the store`() {
        val a = launch()
        a.findViewById<EditText>(R.id.api_key_input).setText("   ")
        a.findViewById<Button>(R.id.save_key_button).performClick()
        assertEquals("Paste a valid key", a.findViewById<EditText>(R.id.api_key_input).error.toString())
        assertTrue(keys.saves.isEmpty())
    }

    @Test fun `saving a pasted key trims it, stores it and never shows it again`() {
        val a = launch()
        val input = a.findViewById<EditText>(R.id.api_key_input)
        input.setText("  test-key-123\n")
        a.findViewById<Button>(R.id.save_key_button).performClick()
        assertEquals(listOf("test-key-123"), keys.saves)
        assertEquals("", input.text.toString())
        assertEquals("Key saved", input.hint.toString())
        assertNull(input.error)
    }

    @Test fun `with a saved key the field hint says so on launch`() {
        keys.stored = "test-key-123"
        assertEquals("Key saved", launch().findViewById<EditText>(R.id.api_key_input).hint.toString())
    }

    @Test fun `test key uses the saved key and reports Key works`() {
        keys.stored = "test-key-123"
        val a = launch()
        a.findViewById<Button>(R.id.test_key_button).performClick(); idle()
        assertEquals(listOf("test-key-123"), checked)
        assertEquals("Key works", a.text(R.id.test_key_result))
    }

    @Test fun `test key prefers a key typed but not yet saved`() {
        keys.stored = "test-key-old"
        val a = launch()
        a.findViewById<EditText>(R.id.api_key_input).setText("test-key-new")
        a.findViewById<Button>(R.id.test_key_button).performClick(); idle()
        assertEquals(listOf("test-key-new"), checked)
    }

    @Test fun `test key maps every result to text`() {
        keys.stored = "test-key-123"
        val a = launch()
        val cases = listOf(
            KeyCheckResult.Rejected to "Key rejected",
            KeyCheckResult.NoConnection to "No connection",
            KeyCheckResult.Failed(500) to "Groq error 500",
            KeyCheckResult.Valid to "Key works",
        )
        cases.forEach { (result, text) ->
            checkResult = result
            a.findViewById<Button>(R.id.test_key_button).performClick(); idle()
            assertEquals(text, a.text(R.id.test_key_result))
        }
    }

    @Test fun `test key without any key asks for one`() {
        val a = launch()
        a.findViewById<Button>(R.id.test_key_button).performClick(); idle()
        assertTrue(checked.isEmpty())
        assertEquals("Paste a key first", a.text(R.id.test_key_result))
    }

    @Test fun `sound and haptic switches persist`() {
        val a = launch()
        a.findViewById<SwitchCompat>(R.id.sound_switch).performClick()
        a.findViewById<SwitchCompat>(R.id.haptic_switch).performClick()
        assertFalse(AppSettings.from(context).soundOn)
        assertFalse(AppSettings.from(context).hapticOn)
        a.findViewById<SwitchCompat>(R.id.sound_switch).performClick()
        assertTrue(AppSettings.from(context).soundOn)
    }

    @Test fun `switches reflect stored settings`() {
        AppSettings.from(context).soundOn = false
        val a = launch()
        assertFalse(a.findViewById<SwitchCompat>(R.id.sound_switch).isChecked)
        assertTrue(a.findViewById<SwitchCompat>(R.id.haptic_switch).isChecked)
    }

    @Test fun `invalid language shows an error and keeps en`() {
        val a = launch()
        val lang = a.findViewById<EditText>(R.id.language_input)
        assertEquals("en", lang.text.toString())
        lang.requestFocus()
        lang.setText("English!")
        lang.clearFocus()
        assertNotNull(lang.error)
        assertEquals("en", AppSettings.from(context).language)
    }

    @Test fun `valid language is normalised and stored`() {
        val a = launch()
        val lang = a.findViewById<EditText>(R.id.language_input)
        lang.requestFocus()
        lang.setText(" FR ")
        lang.clearFocus()
        assertNull(lang.error)
        assertEquals("fr", AppSettings.from(context).language)
    }

    @Test fun `model spinner offers the models and stores the choice`() {
        val a = launch()
        val spinner = a.findViewById<Spinner>(R.id.model_spinner)
        assertEquals(2, spinner.adapter.count)
        assertEquals("whisper-large-v3-turbo", spinner.selectedItem)
        spinner.setSelection(1); idle()
        assertEquals("whisper-large-v3", AppSettings.from(context).model)
    }

    @Test fun `delete with two unsent recordings leaves none and disables the button`() {
        val store = AppStorage.pendingStore(context)
        repeat(2) { i ->
            val f = java.io.File(AppStorage.voiceDir(context), "recording/17000000000$i.ogg").apply { parentFile!!.mkdirs() }
            f.writeBytes(ByteArray(2048))
            store.moveToPending(f)
        }
        val a = launch()
        assertEquals("Unsent recordings: 2", a.text(R.id.unsent_status))
        val delete = a.findViewById<Button>(R.id.delete_unsent_button)
        assertTrue(delete.isEnabled)
        delete.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertEquals(0, store.pendingCount())
        assertEquals("Unsent recordings: 0", a.text(R.id.unsent_status))
        assertFalse(delete.isEnabled)
    }

    @Test fun `delete is disabled with nothing unsent`() {
        assertFalse(launch().findViewById<Button>(R.id.delete_unsent_button).isEnabled)
    }

    @Test fun `enable keyboard opens the system input method settings`() {
        val a = launch()
        a.findViewById<Button>(R.id.enable_keyboard_button).performClick()
        val next: Intent = shadowOf(a).nextStartedActivity
        assertEquals(Settings.ACTION_INPUT_METHOD_SETTINGS, next.action)
    }

    @Test fun `microphone status follows the permission`() {
        val app = context as Application
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val a = launch()
        assertEquals("Microphone not allowed", a.text(R.id.mic_permission_status))
        assertTrue(a.findViewById<Button>(R.id.mic_permission_button).isEnabled)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        controller.pause().resume()
        assertEquals("Microphone allowed", a.text(R.id.mic_permission_status))
        assertFalse(a.findViewById<Button>(R.id.mic_permission_button).isEnabled)
    }

    @Test fun `try it here is a multi-line field with a hint`() {
        val field = launch().findViewById<EditText>(R.id.try_it_here)
        assertTrue(field.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
        assertTrue(field.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0)
        assertEquals("Try it here", field.hint.toString())
        assertTrue(field.minLines >= 4)
    }
}
