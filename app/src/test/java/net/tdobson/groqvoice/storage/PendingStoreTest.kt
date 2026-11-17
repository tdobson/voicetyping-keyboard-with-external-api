package net.tdobson.groqvoice.storage

import net.tdobson.groqvoice.core.controller.Clock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PendingStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_700_000_000_000L
    private val clock = Clock { now }
    private lateinit var root: File
    private lateinit var store: PendingStore

    @Before fun setUp() {
        root = tmp.newFolder("voice")
        store = PendingStore(root, clock)
    }

    private fun recorded(bytes: Int, fill: Byte = 1): File =
        store.newRecordingFile().also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(bytes) { fill }) }

    @Test fun `newRecordingFile is named after the clock in recording dir`() {
        val f = store.newRecordingFile()
        assertEquals(File(root, "recording/1700000000000.ogg"), f)
        assertTrue("recording dir exists", f.parentFile!!.isDirectory)
    }

    @Test fun `second recording file at the same millis gets a unique name`() {
        val a = store.newRecordingFile().also { it.writeBytes(ByteArray(10)) }
        val b = store.newRecordingFile()
        assertEquals("1700000000000.ogg", a.name)
        assertEquals("1700000000000_1.ogg", b.name)
    }

    @Test fun `unique name also avoids a pending file with the same name`() {
        store.moveToPending(recorded(2000))
        val b = store.newRecordingFile()
        assertEquals("1700000000000_1.ogg", b.name)
    }

    @Test fun `moveToPending keeps the name and moves the bytes`() {
        val f = recorded(2000)
        val p = store.moveToPending(f)
        assertEquals(File(root, "pending/1700000000000.ogg"), p)
        assertFalse(f.exists())
        assertEquals(2000L, p.length())
    }

    @Test fun `pending is sorted oldest first and ignores non-ogg files`() {
        now = 3_000; val c = store.moveToPending(recorded(2000))
        now = 1_000; val a = store.moveToPending(recorded(2000))
        now = 2_000; val b = store.moveToPending(recorded(2000))
        File(root, "pending/notes.txt").writeText("x")
        assertEquals(listOf(a, b, c), store.pending())
        assertEquals(3, store.pendingCount())
    }

    @Test fun `pending is empty when nothing was recorded`() {
        assertEquals(emptyList<File>(), store.pending())
        assertEquals(0, store.pendingCount())
    }

    @Test fun `isValid needs more than 1 KB`() {
        assertFalse(store.isValid(File(root, "missing.ogg")))
        assertFalse(store.isValid(recorded(1024)))
        now++
        assertTrue(store.isValid(recorded(1025)))
    }

    @Test fun `markSucceeded keeps the last two in history, newest as 1`() {
        val first = store.moveToPending(recorded(2000, fill = 1)); now++
        val second = store.moveToPending(recorded(2000, fill = 2)); now++
        val third = store.moveToPending(recorded(2000, fill = 3))

        store.markSucceeded(first)
        assertArrayEquals(ByteArray(2000) { 1 }, File(root, "history/1.ogg").readBytes())
        assertFalse(File(root, "history/2.ogg").exists())
        assertFalse(first.exists())

        store.markSucceeded(second)
        assertArrayEquals(ByteArray(2000) { 2 }, File(root, "history/1.ogg").readBytes())
        assertArrayEquals(ByteArray(2000) { 1 }, File(root, "history/2.ogg").readBytes())

        store.markSucceeded(third)
        assertArrayEquals(ByteArray(2000) { 3 }, File(root, "history/1.ogg").readBytes())
        assertArrayEquals(ByteArray(2000) { 2 }, File(root, "history/2.ogg").readBytes())
        assertEquals(2, File(root, "history").listFiles()!!.size)
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `discard deletes the file`() {
        val p = store.moveToPending(recorded(2000))
        store.discard(p)
        assertFalse(p.exists())
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `clearPending deletes all pending and returns the count`() {
        store.moveToPending(recorded(2000)); now++
        store.moveToPending(recorded(2000))
        assertEquals(2, store.clearPending())
        assertEquals(0, store.pendingCount())
        assertEquals(0, store.clearPending())
    }

    @Test fun `lastText round trips newlines and unicode`() {
        assertNull(store.lastText)
        store.lastText = "Café ↺\nline 2"
        assertEquals("Café ↺\nline 2", store.lastText)
        assertEquals("Café ↺\nline 2", PendingStore(root, clock).lastText)
    }

    @Test fun `setting lastText to null deletes it`() {
        store.lastText = "hello"
        store.lastText = null
        assertNull(store.lastText)
        assertFalse(File(root, "last_text.txt").exists())
    }

    @Test fun `recoverInterrupted moves valid leftovers to pending and deletes tiny ones`() {
        val good = recorded(4096); now++
        val tiny = recorded(10)
        assertEquals(1, store.recoverInterrupted())
        assertFalse(good.exists())
        assertFalse(tiny.exists())
        assertEquals(listOf(File(root, "pending/${good.name}")), store.pending())
    }

    @Test fun `recoverInterrupted with nothing to recover returns 0`() {
        assertEquals(0, store.recoverInterrupted())
    }

    @Test fun `a fresh instance on the same root sees the same state`() {
        store.moveToPending(recorded(2000))
        store.lastText = "kept"
        val again = PendingStore(root, clock)
        assertEquals(1, again.pendingCount())
        assertEquals("kept", again.lastText)
    }
}
