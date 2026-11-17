package net.tdobson.groqvoice.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.tdobson.groqvoice.core.net.TranscriptionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppSettingsTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun fresh() = AppSettings.from(context)

    @Test fun `defaults match the desktop`() {
        val s = fresh()
        assertEquals("whisper-large-v3-turbo", s.model)
        assertEquals("en", s.language)
        assertTrue(s.soundOn)
        assertTrue(s.hapticOn)
    }

    @Test fun `each property persists across a new instance`() {
        fresh().apply {
            model = "whisper-large-v3"
            language = "fr"
            soundOn = false
            hapticOn = false
        }
        val s = fresh()
        assertEquals("whisper-large-v3", s.model)
        assertEquals("fr", s.language)
        assertFalse(s.soundOn)
        assertFalse(s.hapticOn)
    }

    @Test fun `a garbage stored model falls back to turbo`() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("model", "gpt-9").commit()
        assertEquals("whisper-large-v3-turbo", fresh().model)
    }

    @Test fun `a garbage stored language falls back to en`() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("language", "English!!").commit()
        assertEquals("en", fresh().language)
    }

    @Test fun `request reflects model and language with fixed temperature and format`() {
        val s = fresh()
        assertEquals(TranscriptionRequest("whisper-large-v3-turbo", "en", "0", "text"), s.request())
        s.model = "whisper-large-v3"
        s.language = "de"
        assertEquals(TranscriptionRequest("whisper-large-v3", "de", "0", "text"), s.request())
    }

    @Test fun `the API key is never stored in settings`() {
        fresh().apply { model = "whisper-large-v3"; language = "fr"; soundOn = false; hapticOn = true }
        val keys = context.getSharedPreferences("settings", Context.MODE_PRIVATE).all.keys
        assertTrue("settings were written", keys.isNotEmpty())
        assertTrue(keys.none { it.contains("key", ignoreCase = true) })
    }
}
