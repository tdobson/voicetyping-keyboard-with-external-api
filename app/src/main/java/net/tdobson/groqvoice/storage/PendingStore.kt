package net.tdobson.groqvoice.storage

import net.tdobson.groqvoice.core.controller.Clock
import net.tdobson.groqvoice.core.controller.RecordingStore
import java.io.File
import java.io.IOException

/**
 * Recordings on disk, in app-private storage:
 * - root/recording/  the file being recorded right now (not "unsent" yet)
 * - root/pending/    stopped recordings not yet delivered (the "⟳ N unsent" chip)
 * - root/history/1.ogg (most recent success) and 2.ogg, like the desktop's last two recordings
 * - root/last_text.txt  the last transcription, for ↺
 *
 * Plain java.io so it is tested on the JVM. All calls come from the main thread.
 */
class PendingStore(
    private val root: File,
    private val clock: Clock,
    private val minValidBytes: Long = 1024,
) : RecordingStore {
    private val recordingDir = File(root, "recording")
    private val pendingDir = File(root, "pending")
    private val historyDir = File(root, "history")
    private val lastTextFile = File(root, "last_text.txt")

    override fun newRecordingFile(): File {
        recordingDir.mkdirs()
        val base = clock.nowMs().toString()
        var n = 0
        while (true) {
            val name = if (n == 0) "$base.ogg" else "${base}_$n.ogg"
            val f = File(recordingDir, name)
            if (!f.exists() && !File(pendingDir, name).exists()) return f
            n++
        }
    }

    override fun moveToPending(file: File): File {
        pendingDir.mkdirs()
        val target = File(pendingDir, file.name)
        move(file, target)
        return target
    }

    override fun pending(): List<File> =
        pendingDir.listFiles { f -> f.isFile && f.name.endsWith(".ogg") }
            .orEmpty()
            .sortedWith(compareBy<File>({ millisOf(it) }, { suffixOf(it) }, { it.name }))

    fun pendingCount(): Int = pending().size

    override fun isValid(file: File): Boolean = file.isFile && file.length() > minValidBytes

    override fun markSucceeded(file: File) {
        historyDir.mkdirs()
        val newest = File(historyDir, "1.ogg")
        val older = File(historyDir, "2.ogg")
        if (newest.exists()) {
            older.delete()
            move(newest, older)
        }
        move(file, newest)
    }

    override fun discard(file: File) {
        file.delete()
    }

    /** Moves valid leftovers from a recording interrupted by the service dying into pending/; deletes the rest. */
    fun recoverInterrupted(): Int {
        var moved = 0
        recordingDir.listFiles().orEmpty().filter { it.isFile }.forEach { f ->
            if (isValid(f)) {
                moveToPending(f)
                moved++
            } else {
                f.delete()
            }
        }
        return moved
    }

    /** Settings "Delete unsent". Returns how many were deleted. */
    fun clearPending(): Int = pending().count { it.delete() }

    override var lastText: String?
        get() = if (lastTextFile.isFile) lastTextFile.readText(Charsets.UTF_8) else null
        set(value) {
            if (value == null) {
                lastTextFile.delete()
                return
            }
            root.mkdirs()
            val tmp = File(root, "last_text.txt.tmp")
            tmp.writeText(value, Charsets.UTF_8)
            move(tmp, lastTextFile)
        }

    private fun move(from: File, to: File) {
        if (to.exists()) to.delete()
        if (from.renameTo(to)) return
        // Same filesystem in practice; fall back to copy + delete if rename is refused.
        try {
            from.copyTo(to, overwrite = true)
            from.delete()
        } catch (e: IOException) {
            throw IOException("could not move ${from.name}", e)
        }
    }

    private fun millisOf(f: File): Long = f.name.substringBefore('.').substringBefore('_').toLongOrNull() ?: Long.MAX_VALUE

    private fun suffixOf(f: File): Int = f.name.substringBefore('.').substringAfter('_', "0").toIntOrNull() ?: 0
}
