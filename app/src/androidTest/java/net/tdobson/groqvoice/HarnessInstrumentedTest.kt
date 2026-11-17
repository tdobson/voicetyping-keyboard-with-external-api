package net.tdobson.groqvoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Layer 4 placeholder: proves the instrumented runner works on the emulator. */
@RunWith(AndroidJUnit4::class)
class HarnessInstrumentedTest {
    @Before
    fun emulatorOnly() = assumeTrue("instrumented tests run on the emulator only", EmulatorOnly.isEmulator())

    @Test
    fun targetContextIsOurPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("net.tdobson.groqvoice", context.packageName)
    }
}
