package net.tdobson.groqvoice.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsRulesTest {
    @Test fun `pasted key is trimmed including newlines`() =
        assertEquals("test-key-123", SettingsRules.normaliseKey("  test-key-123\n"))

    @Test fun `blank key is no key`() {
        assertNull(SettingsRules.normaliseKey(""))
        assertNull(SettingsRules.normaliseKey("  "))
    }

    @Test fun `key with inner whitespace is invalid`() {
        assertNull(SettingsRules.normaliseKey("abc def"))
        assertNull(SettingsRules.normaliseKey("abc\ndef"))
    }

    @Test fun `language is trimmed and lowercased`() = assertEquals("en", SettingsRules.normaliseLanguage("EN "))
    @Test fun `blank language defaults to english`() = assertEquals("en", SettingsRules.normaliseLanguage(""))
    @Test fun `language names are invalid`() = assertNull(SettingsRules.normaliseLanguage("english"))
    @Test fun `two letter code is kept`() = assertEquals("de", SettingsRules.normaliseLanguage("de"))
    @Test fun `three letter code is kept`() = assertEquals("yue", SettingsRules.normaliseLanguage("yue"))
    @Test fun `digits are invalid`() = assertNull(SettingsRules.normaliseLanguage("e1"))

    @Test fun `known model is kept`() = assertEquals("whisper-large-v3", SettingsRules.modelOrDefault("whisper-large-v3"))
    @Test fun `unknown model falls back to turbo`() = assertEquals("whisper-large-v3-turbo", SettingsRules.modelOrDefault("gpt"))
    @Test fun `missing model falls back to turbo`() = assertEquals("whisper-large-v3-turbo", SettingsRules.modelOrDefault(null))

    @Test fun `model list and defaults`() {
        assertEquals(listOf("whisper-large-v3-turbo", "whisper-large-v3"), SettingsRules.MODELS)
        assertEquals("whisper-large-v3-turbo", SettingsRules.DEFAULT_MODEL)
        assertEquals("en", SettingsRules.DEFAULT_LANGUAGE)
    }
}
