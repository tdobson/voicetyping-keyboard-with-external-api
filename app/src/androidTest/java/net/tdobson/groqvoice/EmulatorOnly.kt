package net.tdobson.groqvoice

import android.os.Build

/**
 * Every instrumented test class starts with assumeTrue(EmulatorOnly.isEmulator()) so it can never change
 * IME settings or record audio on a real phone, even if launched there by mistake.
 */
object EmulatorOnly {
    fun isEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic") || Build.PRODUCT.startsWith("sdk") ||
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")
}
