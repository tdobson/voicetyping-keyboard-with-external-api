package net.tdobson.groqvoice.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.tdobson.groqvoice.R
import net.tdobson.groqvoice.core.net.GroqClient
import net.tdobson.groqvoice.core.net.KeyCheckResult
import net.tdobson.groqvoice.core.settings.SettingsRules
import net.tdobson.groqvoice.security.ApiKeyStore
import net.tdobson.groqvoice.security.KeyStorage
import net.tdobson.groqvoice.storage.AppStorage
import net.tdobson.groqvoice.storage.PendingStore

/**
 * The launcher screen: API key, microphone permission (an IME cannot ask for it), keyboard shortcuts, model,
 * language, feedback toggles, unsent recordings, and a "Try it here" field to use the keyboard straight away.
 * The key is never shown again once saved and never logged.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var keys: KeyStorage
    private lateinit var settings: AppSettings
    private lateinit var pending: PendingStore

    private lateinit var keyInput: EditText
    private lateinit var keyResult: TextView
    private lateinit var micStatus: TextView
    private lateinit var micButton: Button
    private lateinit var languageInput: EditText
    private lateinit var unsentStatus: TextView
    private lateinit var deleteUnsent: Button

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshMic() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applyInsets(findViewById(android.R.id.content))
        keys = keyStoreFactory(this)
        settings = AppSettings.from(this)
        pending = AppStorage.pendingStore(this)

        keyInput = findViewById(R.id.api_key_input)
        keyResult = findViewById(R.id.test_key_result)
        micStatus = findViewById(R.id.mic_permission_status)
        micButton = findViewById(R.id.mic_permission_button)
        languageInput = findViewById(R.id.language_input)
        unsentStatus = findViewById(R.id.unsent_status)
        deleteUnsent = findViewById(R.id.delete_unsent_button)

        findViewById<Button>(R.id.save_key_button).setOnClickListener { saveKey() }
        findViewById<Button>(R.id.test_key_button).setOnClickListener { testKey() }
        micButton.setOnClickListener { askForMic() }
        findViewById<Button>(R.id.enable_keyboard_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.choose_keyboard_button).setOnClickListener {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
        setUpModel()
        setUpLanguage()
        setUpSwitch(R.id.sound_switch, settings.soundOn) { settings.soundOn = it }
        setUpSwitch(R.id.haptic_switch, settings.hapticOn) { settings.hapticOn = it }
        deleteUnsent.setOnClickListener { confirmDeleteUnsent() }
        refreshKeyHint()
    }

    override fun onResume() {
        super.onResume()
        refreshMic()
        refreshUnsent()
    }

    override fun onPause() {
        commitLanguage()
        super.onPause()
    }

    /**
     * targetSdk 35+ is edge-to-edge: keep content clear of the system bars and above the keyboard. The padding
     * goes on the content parent so the ScrollView shrinks and scrolls the focused field into view itself.
     */
    private fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun saveKey() {
        val key = SettingsRules.normaliseKey(keyInput.text.toString())
        if (key == null) {
            keyInput.error = getString(R.string.api_key_invalid)
            return
        }
        keys.save(key)
        keyInput.text.clear()
        keyInput.error = null
        keyResult.text = ""
        refreshKeyHint()
    }

    private fun testKey() {
        val key = SettingsRules.normaliseKey(keyInput.text.toString()) ?: keys.apiKey()
        if (key == null) {
            keyResult.text = getString(R.string.key_missing)
            return
        }
        keyResult.text = getString(R.string.key_testing)
        lifecycleScope.launch {
            keyResult.text = when (val r = keyChecker(key)) {
                KeyCheckResult.Valid -> getString(R.string.key_works)
                KeyCheckResult.Rejected -> getString(R.string.key_rejected)
                KeyCheckResult.NoConnection -> getString(R.string.key_no_connection)
                is KeyCheckResult.Failed -> getString(R.string.key_groq_error, r.code)
            }
        }
    }

    private fun refreshKeyHint() {
        keyInput.hint = getString(if (keys.hasKey()) R.string.api_key_saved_hint else R.string.api_key_hint)
    }

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun refreshMic() {
        val granted = micGranted()
        micStatus.text = getString(if (granted) R.string.mic_allowed else R.string.mic_not_allowed)
        micButton.isEnabled = !granted
        micButton.text = getString(if (!granted && permanentlyDenied()) R.string.open_app_settings else R.string.allow_microphone)
    }

    /** Asked before and Android will no longer show the dialog: only app settings can grant it now. */
    private fun permanentlyDenied(): Boolean =
        uiState().getBoolean(MIC_ASKED, false) && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)

    private fun askForMic() {
        if (micGranted()) return
        if (permanentlyDenied()) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            return
        }
        uiState().edit { putBoolean(MIC_ASKED, true) }
        requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun setUpModel() {
        val spinner = findViewById<Spinner>(R.id.model_spinner)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, SettingsRules.MODELS)
        spinner.setSelection(SettingsRules.MODELS.indexOf(settings.model).coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                settings.model = SettingsRules.MODELS[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setUpLanguage() {
        languageInput.setText(settings.language)
        languageInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitLanguage() }
        languageInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitLanguage()
                v.clearFocus()
            }
            false
        }
    }

    /** Valid codes are stored normalised; anything else shows an error and the stored value is kept. */
    private fun commitLanguage() {
        val code = SettingsRules.normaliseLanguage(languageInput.text.toString())
        if (code == null) {
            languageInput.error = getString(R.string.language_invalid)
            return
        }
        languageInput.error = null
        settings.language = code
        if (languageInput.text.toString() != code) languageInput.setText(code)
    }

    private fun setUpSwitch(id: Int, initial: Boolean, write: (Boolean) -> Unit) {
        findViewById<SwitchCompat>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> write(checked) }
        }
    }

    private fun refreshUnsent() {
        val n = pending.pendingCount()
        unsentStatus.text = getString(R.string.unsent_count, n)
        deleteUnsent.isEnabled = n > 0
    }

    private fun confirmDeleteUnsent() {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_unsent_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                pending.clearPending()
                refreshUnsent()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun uiState() = getSharedPreferences("ui_state", Context.MODE_PRIVATE)

    companion object {
        private const val MIC_ASKED = "mic_asked"

        private val defaultKeyStoreFactory: (Context) -> KeyStorage = { ApiKeyStore.from(it) }
        private val defaultKeyChecker: suspend (String) -> KeyCheckResult = { key ->
            GroqClient(GroqClient.defaultHttpClient()).checkKey(key)
        }

        /** Test seams: Robolectric has no AndroidKeyStore and tests never call Groq. */
        @VisibleForTesting var keyStoreFactory: (Context) -> KeyStorage = defaultKeyStoreFactory
        @VisibleForTesting var keyChecker: suspend (String) -> KeyCheckResult = defaultKeyChecker

        @VisibleForTesting fun resetSeams() {
            keyStoreFactory = defaultKeyStoreFactory
            keyChecker = defaultKeyChecker
        }
    }
}
