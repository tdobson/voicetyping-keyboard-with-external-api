package net.tdobson.groqvoice.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import net.tdobson.groqvoice.core.controller.RequestProvider
import net.tdobson.groqvoice.core.net.TranscriptionRequest
import net.tdobson.groqvoice.core.settings.SettingsRules

/** User settings (model, language, feedback toggles). The API key lives in ApiKeyStore, never here. */
class AppSettings(private val prefs: SharedPreferences) : RequestProvider {
    var model: String
        get() = SettingsRules.modelOrDefault(prefs.getString(MODEL, null))
        set(value) = prefs.edit { putString(MODEL, SettingsRules.modelOrDefault(value)) }

    var language: String
        get() = prefs.getString(LANGUAGE, null)?.let(SettingsRules::normaliseLanguage) ?: SettingsRules.DEFAULT_LANGUAGE
        set(value) = prefs.edit { putString(LANGUAGE, SettingsRules.normaliseLanguage(value) ?: SettingsRules.DEFAULT_LANGUAGE) }

    var soundOn: Boolean
        get() = prefs.getBoolean(SOUND, true)
        set(value) = prefs.edit { putBoolean(SOUND, value) }

    var hapticOn: Boolean
        get() = prefs.getBoolean(HAPTIC, true)
        set(value) = prefs.edit { putBoolean(HAPTIC, value) }

    /** voiceTyping.js form fields: temperature 0 and response_format text are fixed. */
    override fun request(): TranscriptionRequest = TranscriptionRequest(model = model, language = language)

    companion object {
        private const val MODEL = "model"
        private const val LANGUAGE = "language"
        private const val SOUND = "sound_on"
        private const val HAPTIC = "haptic_on"

        fun from(context: Context): AppSettings =
            AppSettings(context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE))
    }
}
