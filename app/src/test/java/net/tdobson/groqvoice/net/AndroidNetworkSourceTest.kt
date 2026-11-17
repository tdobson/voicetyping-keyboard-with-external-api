package net.tdobson.groqvoice.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/** The Android adapter over ConnectivityManager, with Robolectric's shadow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidNetworkSourceTest {
    private lateinit var cm: ConnectivityManager

    @Before fun setUp() {
        cm = ApplicationProvider.getApplicationContext<Context>().getSystemService(ConnectivityManager::class.java)
    }

    private fun caps(validated: Boolean): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @Test fun validatedActiveNetworkIsValidatedNow() {
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps(validated = true))
        assertTrue(AndroidNetworkSource(cm).isValidatedNow())
    }

    @Test fun unvalidatedActiveNetworkIsNotValidated() {
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps(validated = false))
        assertFalse(AndroidNetworkSource(cm).isValidatedNow())
    }

    @Test fun noActiveNetworkIsNotValidated() {
        shadowOf(cm).setDefaultNetworkActive(false)
        shadowOf(cm).setActiveNetworkInfo(null)
        assertFalse(AndroidNetworkSource(cm).isValidatedNow())
    }

    @Test fun registerListensForValidatedCapabilityAndUnregisterRemovesTheCallback() {
        var calls = 0
        val unregister = AndroidNetworkSource(cm).register { calls++ }
        val callbacks = shadowOf(cm).networkCallbacks
        assertEquals(1, callbacks.size)
        val cb = callbacks.single()
        cb.onCapabilitiesChanged(cm.activeNetwork!!, caps(validated = false))
        assertEquals(0, calls)
        cb.onCapabilitiesChanged(cm.activeNetwork!!, caps(validated = true))
        assertEquals(1, calls)
        unregister()
        assertTrue(shadowOf(cm).networkCallbacks.isEmpty())
    }
}
