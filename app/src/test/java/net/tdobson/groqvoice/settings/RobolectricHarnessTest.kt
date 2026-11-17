package net.tdobson.groqvoice.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Layer 3 placeholder: proves Robolectric loads the app's manifest and resources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobolectricHarnessTest {
    @Test
    fun applicationContextIsOurPackage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("net.tdobson.groqvoice", context.packageName)
        assertEquals("Groq Voice", context.getString(net.tdobson.groqvoice.R.string.app_name))
    }
}
