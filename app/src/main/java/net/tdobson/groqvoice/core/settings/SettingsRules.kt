package net.tdobson.groqvoice.core.settings

/** Validation and defaults for user settings. */
object SettingsRules {
    val MODELS = listOf("whisper-large-v3-turbo", "whisper-large-v3")
    const val DEFAULT_MODEL = "whisper-large-v3-turbo"
    const val DEFAULT_LANGUAGE = "en"

    private val LANGUAGE = Regex("^[a-z]{2,3}$")

    /** Trims a pasted key (including newlines). Blank or containing inner whitespace gives null. */
    fun normaliseKey(raw: String): String? {
        val key = raw.trim()
        if (key.isEmpty() || key.any(Char::isWhitespace)) return null
        return key
    }

    /** Trim and lowercase; blank gives "en"; anything but a 2-3 letter code is invalid (null). */
    fun normaliseLanguage(raw: String): String? {
        val code = raw.trim().lowercase()
        if (code.isEmpty()) return DEFAULT_LANGUAGE
        return code.takeIf(LANGUAGE::matches)
    }

    fun modelOrDefault(stored: String?): String = stored?.takeIf { it in MODELS } ?: DEFAULT_MODEL
}
