package net.tdobson.groqvoice.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The merged manifest: what is exported, what is requested, and no backups. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val info = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS,
    )

    @Test fun `only the IME service and the launcher activity are exported`() {
        val exported = buildList {
            info.activities?.filter { it.exported }?.forEach { add(it.name) }
            info.services?.filter { it.exported }?.forEach { add(it.name) }
            info.receivers?.filter { it.exported }?.forEach { add(it.name) }
            info.providers?.filter { it.exported }?.forEach { add(it.name) }
        }
        assertEquals(
            setOf("net.tdobson.groqvoice.settings.SettingsActivity", "net.tdobson.groqvoice.ime.VoiceKeyboardService"),
            exported.toSet(),
        )
    }

    @Test fun `the IME service is bound only by the system`() {
        val ime = info.services!!.single { it.name.endsWith(".ime.VoiceKeyboardService") }
        assertEquals("android.permission.BIND_INPUT_METHOD", ime.permission)
    }

    @Test fun `requests exactly the permissions the spec lists`() {
        val wanted = setOf(
            "android.permission.RECORD_AUDIO", "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE", "android.permission.VIBRATE",
        )
        val requested = info.requestedPermissions!!.toSet()
        assertTrue("missing ${wanted - requested}", requested.containsAll(wanted))
        // AndroidX adds its own dynamic-receiver permission; nothing else may sneak in.
        val extra = requested - wanted
        assertTrue("unexpected $extra", extra.all { it.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") })
    }

    @Test fun `backup is off`() {
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
