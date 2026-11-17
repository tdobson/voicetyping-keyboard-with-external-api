package net.tdobson.groqvoice.audio

import android.Manifest
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import net.tdobson.groqvoice.EmulatorOnly
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RecorderTest {
    @get:Rule val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private lateinit var context: Context
    private lateinit var dir: File
    private val recorders = mutableListOf<Recorder>()

    @Before fun setUp() {
        assumeTrue("instrumented tests run on the emulator only", EmulatorOnly.isEmulator())
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(context.cacheDir, "recorder-test").apply { deleteRecursively(); mkdirs() }
    }

    @After fun tearDown() {
        recorders.forEach { it.cancel() }
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun recorder(maxMs: Int = 60 * 60 * 1000) = Recorder(context, maxMs).also { recorders += it }

    @Test fun recordsOggOpus16kMono() {
        val f = File(dir, "a.ogg")
        val r = recorder()
        assertTrue(r.start(f) {})
        SystemClock.sleep(3_000)
        assertTrue("stop finalises the file", r.stop())
        assertValidOggOpus(f, minDurationMs = 2_500)
    }

    @Test fun cancelDeletesTheFile() {
        val f = File(dir, "b.ogg")
        val r = recorder()
        assertTrue(r.start(f) {})
        SystemClock.sleep(1_000)
        r.cancel()
        assertFalse(f.exists())
    }

    @Test fun stopRightAfterStartReturnsFalseAndLeavesNoFile() {
        val f = File(dir, "c.ogg")
        val r = recorder()
        assertTrue(r.start(f) {})
        SystemClock.sleep(50)
        assertFalse(r.stop())
        assertFalse(f.exists())
    }

    @Test fun startTwiceWithoutStopIsRefused() {
        val r = recorder()
        assertTrue(r.start(File(dir, "d.ogg")) {})
        assertFalse(r.start(File(dir, "e.ogg")) {})
        assertFalse(File(dir, "e.ogg").exists())
        SystemClock.sleep(1_500)
        assertTrue("first recording still works", r.stop())
    }

    @Test fun recorderIsReusableAfterStop() {
        val r = recorder()
        assertTrue(r.start(File(dir, "f1.ogg")) {})
        SystemClock.sleep(1_500)
        assertTrue(r.stop())
        val f2 = File(dir, "f2.ogg")
        assertTrue(r.start(f2) {})
        SystemClock.sleep(1_500)
        assertTrue(r.stop())
        assertTrue(f2.length() > 0)
    }

    @Test fun stopAndCancelWhenIdleDoNotThrow() {
        val r = recorder()
        assertFalse(r.stop())
        r.cancel()
    }

    @Test fun maxDurationCallbackFiresAndTheFileIsStillKept() {
        val fired = CountDownLatch(1)
        val f = File(dir, "g.ogg")
        val r = recorder(maxMs = 1_500)
        assertTrue(r.start(f) { fired.countDown() })
        assertTrue("max duration callback within 4 s", fired.await(4, TimeUnit.SECONDS))
        assertTrue("stop after max duration keeps the audio", r.stop())
        assertValidOggOpus(f, minDurationMs = 1_000)
    }

    private fun assertValidOggOpus(f: File, minDurationMs: Long) {
        assertTrue("file exists", f.isFile)
        val bytes = f.readBytes()
        assertTrue("larger than 1 KB, was ${bytes.size}", bytes.size > 1024)
        assertEquals("OggS", String(bytes, 0, 4, Charsets.US_ASCII))
        val head = indexOf(bytes, "OpusHead".toByteArray(Charsets.US_ASCII))
        assertTrue("OpusHead in the first page", head in 0 until 200)
        val channels = bytes[head + 9].toInt() and 0xff
        val inputRate = (bytes[head + 12].toInt() and 0xff) or
            ((bytes[head + 13].toInt() and 0xff) shl 8) or
            ((bytes[head + 14].toInt() and 0xff) shl 16) or
            ((bytes[head + 15].toInt() and 0xff) shl 24)
        assertEquals("OpusHead channel count", 1, channels)
        assertEquals("OpusHead input sample rate", 16_000, inputRate)

        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            assertEquals(1, ex.trackCount)
            val fmt = ex.getTrackFormat(0)
            assertEquals("audio/opus", fmt.getString(MediaFormat.KEY_MIME))
            assertEquals(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        } finally {
            ex.release()
        }
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(f.absolutePath)
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue("duration $durationMs ms >= $minDurationMs", durationMs >= minDurationMs)
        } finally {
            mmr.release()
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
