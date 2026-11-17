package net.tdobson.groqvoice.core.controller

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import net.tdobson.groqvoice.core.net.TranscriptionApi
import net.tdobson.groqvoice.core.net.TranscriptionRequest
import net.tdobson.groqvoice.core.net.TranscriptionResult
import java.io.File

val F1 = FieldIdentity("com.example.chat", 7, session = 1)
val F2 = FieldIdentity("com.example.mail", 3, session = 4)

/** Returns scripted results in order; the last one repeats. Optional per-call delay (virtual time). */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeApi(private val scheduler: TestCoroutineScheduler, vararg scripted: TranscriptionResult) : TranscriptionApi {
    private val results = scripted.toMutableList()
    private var next = 0
    /** Delay (ms) before answering call n (0-based). */
    val delays = mutableMapOf<Int, Long>()
    val calls = mutableListOf<Pair<File, String>>()
    val callTimes = mutableListOf<Long>()
    val requests = mutableListOf<TranscriptionRequest>()
    val fileExistedAtCall = mutableListOf<Boolean>()

    fun then(vararg more: TranscriptionResult) { results += more }

    override suspend fun transcribe(audio: File, apiKey: String, request: TranscriptionRequest): TranscriptionResult {
        val n = calls.size
        calls += audio to apiKey
        callTimes += scheduler.currentTime
        requests += request
        fileExistedAtCall += audio.isFile
        delays[n]?.let { delay(it) }
        val r = results[minOf(next, results.lastIndex)]
        next++
        return r
    }
}

/** Counts waits. While [blocked], waits suspend until [release] (or cancellation). */
class FakeGate : ConnectivityGate {
    var calls = 0
    var blocked = false
    private val waiters = mutableListOf<CompletableDeferred<Unit>>()

    override suspend fun awaitOnline() {
        calls++
        if (blocked) CompletableDeferred<Unit>().also { waiters += it }.await()
    }

    fun release() {
        blocked = false
        waiters.forEach { it.complete(Unit) }
        waiters.clear()
    }
}

class FakeRecorder(var bytesOnStop: Int = 4096, var failStart: Boolean = false) : AudioRecorder {
    var file: File? = null
    var started = 0
    var stopped = 0
    var cancelled = false
    private var onMax: (() -> Unit)? = null

    override fun start(file: File, onMaxDuration: () -> Unit): Boolean {
        if (failStart) return false
        started++
        this.file = file
        onMax = onMaxDuration
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(0))
        return true
    }

    override fun stop(): Boolean {
        stopped++
        file!!.writeBytes(ByteArray(bytesOnStop) { 7 })
        return true
    }

    override fun cancel() {
        cancelled = true
        file?.delete()
    }

    fun hitMaxDuration() = onMax!!.invoke()
}

/** In-memory bookkeeping over real files in a temporary directory. */
class FakeStore(private val root: File) : RecordingStore {
    private var counter = 0
    private val recordingDir = File(root, "recording").apply { mkdirs() }
    private val pendingDir = File(root, "pending").apply { mkdirs() }
    private val historyDir = File(root, "history").apply { mkdirs() }
    val history = mutableListOf<File>()
    val discarded = mutableListOf<String>()
    override var lastText: String? = null

    override fun newRecordingFile() = File(recordingDir, "%06d.ogg".format(++counter))

    override fun moveToPending(file: File): File = File(pendingDir, file.name).also { check(file.renameTo(it)) }

    override fun pending(): List<File> = pendingDir.listFiles().orEmpty().sortedBy { it.name }

    override fun isValid(file: File) = file.isFile && file.length() > 1024

    override fun markSucceeded(file: File) {
        val dest = File(historyDir, file.name)
        check(file.renameTo(dest))
        history += dest
        while (history.size > 2) history.removeAt(0).delete()
    }

    override fun discard(file: File) {
        discarded += file.name
        file.delete()
    }

    /** A recording left unsent by an earlier session. */
    fun seedPending(bytes: Int = 4096): File =
        File(pendingDir, "%06d.ogg".format(++counter)).apply { writeBytes(ByteArray(bytes) { 1 }) }

    fun recordingFiles(): List<File> = recordingDir.listFiles().orEmpty().toList()
}

class FakeDelivery(var field: FieldIdentity? = F1, var before: Char? = 'x') : DeliveryTarget {
    val committed = mutableListOf<String>()
    var clipboard: String? = null
    override fun currentField() = field
    override fun charBeforeCursor() = before
    override fun commit(text: String) { committed += text }
    override fun copyToClipboard(text: String) { clipboard = text }
}

class RecordingListener : ControllerListener {
    val statuses = mutableListOf<Status>()
    val cues = mutableListOf<Cue>()
    val pendingCounts = mutableListOf<Int>()
    override fun onStatus(status: Status) { statuses += status }
    override fun onPendingCount(count: Int) { pendingCounts += count }
    override fun onCue(cue: Cue) { cues += cue }
}
