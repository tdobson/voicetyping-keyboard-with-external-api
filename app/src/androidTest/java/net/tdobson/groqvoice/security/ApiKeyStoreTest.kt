package net.tdobson.groqvoice.security

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.tdobson.groqvoice.EmulatorOnly
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
class ApiKeyStoreTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var alias: String
    private lateinit var prefsName: String

    @Before fun setUp() {
        assumeTrue("instrumented tests run on the emulator only", EmulatorOnly.isEmulator())
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString().take(8)
        alias = "test_kek_$id"
        prefsName = "secure_test_$id"
        prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    @After fun tearDown() {
        if (!::alias.isInitialized) return
        androidKeyStore().deleteEntry(alias)
        prefs.edit().clear().commit()
        context.deleteSharedPreferences(prefsName)
    }

    private fun androidKeyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun store() = ApiKeyStore(prefs, alias)

    @Test fun absentKeyIsNull() {
        assertNull(store().apiKey())
        assertFalse(store().hasKey())
    }

    @Test fun roundTrip() {
        store().save("test-key-123")
        assertEquals("test-key-123", store().apiKey())
        assertTrue(store().hasKey())
    }

    @Test fun overwriteReturnsTheNewValue() {
        val s = store()
        s.save("test-key-one")
        s.save("test-key-two")
        assertEquals("test-key-two", s.apiKey())
    }

    @Test fun clearGivesNull() {
        val s = store()
        s.save("test-key-123")
        s.clear()
        assertNull(s.apiKey())
        assertFalse(s.hasKey())
    }

    @Test fun aNewInstanceOnTheSamePrefsReadsTheSameKey() {
        store().save("test-key-123")
        val again = ApiKeyStore(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE), alias)
        assertEquals("test-key-123", again.apiKey())
    }

    @Test fun rawPrefsDoNotContainThePlaintext() {
        val secret = "test-key-plaintext-marker-42"
        store().save(secret)
        val raw = prefs.all.values.joinToString("|") { it.toString() }
        assertTrue("something was stored", raw.isNotEmpty())
        assertFalse(raw.contains(secret))
        assertFalse(raw.contains(Base64.getEncoder().encodeToString(secret.toByteArray())))
    }

    @Test fun tamperedCiphertextGivesNullAndClears() {
        store().save("test-key-123")
        val name = prefs.all.keys.single { it.contains("ct") }
        val bytes = Base64.getDecoder().decode(prefs.getString(name, null))
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        prefs.edit().putString(name, Base64.getEncoder().encodeToString(bytes)).commit()
        val s = store()
        assertNull(s.apiKey())
        assertFalse(s.hasKey())
    }

    @Test fun missingKeystoreEntryWithCiphertextGivesNullWithoutCrash() {
        store().save("test-key-123")
        androidKeyStore().deleteEntry(alias)
        val s = store()
        assertNull(s.apiKey())
        assertFalse(s.hasKey())
    }

    @Test fun keyLivesInAndroidKeyStoreAndIsNotExtractable() {
        store().save("test-key-123")
        val ks = androidKeyStore()
        assertTrue(ks.containsAlias(alias))
        val key = ks.getKey(alias, null) as SecretKey
        assertEquals("AES", key.algorithm)
        assertNull("Keystore key material must not be exportable", key.encoded)
    }
}
