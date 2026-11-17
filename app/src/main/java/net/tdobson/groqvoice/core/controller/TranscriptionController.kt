package net.tdobson.groqvoice.core.controller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.tdobson.groqvoice.core.net.FailureClass
import net.tdobson.groqvoice.core.net.FailureClassifier
import net.tdobson.groqvoice.core.net.TranscriptionApi
import net.tdobson.groqvoice.core.net.TranscriptionResult
import net.tdobson.groqvoice.core.retry.RetryPolicy
import net.tdobson.groqvoice.core.text.TranscriptCleaner
import java.io.File

/**
 * Orchestrates record, send with retries, clean and deliver. Depends only on ports, so the whole flow runs as a
 * JVM test.
 *
 * Threading: every public method must be called on the thread of [scope]'s dispatcher (the main thread in the
 * IME), and the send jobs run there too, so the controller's state needs no locking.
 *
 * Audio is never lost: a stopped recording sits in pending/ until it is delivered or Whisper heard nothing.
 * Failures, cancellation and the scope dying all leave it there for the "unsent" chip.
 *
 * While a new recording is in progress, earlier jobs keep working but do not touch the status line and play no
 * cues (a retry tone would end up in the new recording).
 */
class TranscriptionController(
    private val scope: CoroutineScope,
    private val api: TranscriptionApi,
    private val gate: ConnectivityGate,
    private val recorder: AudioRecorder,
    private val store: RecordingStore,
    private val keys: ApiKeyProvider,
    private val requests: RequestProvider,
    private val mic: MicPermission,
    private val delivery: DeliveryTarget,
    private val listener: ControllerListener,
    private val clock: Clock,
    private val retry: RetryPolicy = RetryPolicy(),
) {
    private var recordingFile: File? = null
    private var recordingStartedAt = 0L

    /** Sends run one at a time, in the order the recordings were stopped. */
    private val sendLock = Mutex()
    private val sendJobs = mutableSetOf<Job>()
    /** Files queued or being sent, so retryPending never sends one twice. */
    private val queuedFiles = mutableSetOf<File>()
    /** In-flight status of the job currently sending, or null. */
    private var activeJobStatus: Status? = null

    val isRecording: Boolean get() = recordingFile != null

    /** GestureEvent.Hold. Refused (false) without a key, without mic permission, or if the recorder fails. */
    fun startRecording(): Boolean {
        if (isRecording) return false
        if (keys.apiKey() == null) return refuse(Status.NoApiKey)
        if (!mic.granted()) return refuse(Status.NoMicPermission)
        val file = store.newRecordingFile()
        if (!recorder.start(file) { onMaxDuration(file) }) return refuse(Status.RecordingFailed)
        recordingFile = file
        recordingStartedAt = clock.nowMs()
        listener.onCue(Cue.RecordStart)
        listener.onStatus(Status.Recording(recordingStartedAt, locked = false))
        return true
    }

    /** GestureEvent.Lock. */
    fun lock() {
        if (!isRecording) return
        listener.onStatus(Status.Recording(recordingStartedAt, locked = true))
    }

    /** GestureEvent.Release, the Stop tap, the max-duration limit, or the keyboard being hidden. */
    fun stopAndTranscribe() {
        val file = recordingFile ?: return
        recordingFile = null
        val finalised = recorder.stop()
        listener.onCue(Cue.RecordStop)
        val target = delivery.currentField()
        if (!finalised || !store.isValid(file)) {
            store.discard(file)
            listener.onStatus(Status.RecordingFailed)
            return
        }
        val pending = store.moveToPending(file)
        refreshPendingCount()
        listener.onStatus(activeJobStatus ?: Status.Transcribing)
        enqueue(pending, target)
    }

    /** GestureEvent.Cancel (slide left): discard the audio. */
    fun cancelRecording() {
        val file = recordingFile ?: return
        discardRecording(file)
        listener.onStatus(activeJobStatus ?: Status.Idle)
    }

    /** GestureEvent.TooShort: discard the audio and hint how to use the button. */
    fun discardTooShort() {
        val file = recordingFile ?: return
        discardRecording(file)
        listener.onStatus(Status.HoldToTalk)
    }

    /** The ✕ on the status line: stop every in-flight and queued send. Their audio stays pending. */
    fun cancelTranscription() {
        if (sendJobs.isEmpty()) return
        sendJobs.toList().forEach { it.cancel() }
        sendJobs.clear()
        queuedFiles.clear()
        activeJobStatus = null
        if (!isRecording) listener.onStatus(Status.Cancelled)
        refreshPendingCount()
    }

    /** The "⟳ N unsent" chip: send every pending recording, oldest first, to the field that has focus now. */
    fun retryPending() {
        val target = delivery.currentField()
        store.pending().filterNot { it in queuedFiles }.forEach { enqueue(it, target) }
    }

    /** ↺: insert the last transcription again. */
    fun reinsertLast() {
        val text = store.lastText ?: return
        delivery.commit(withLeadingSpace(text))
    }

    fun refreshPendingCount() = listener.onPendingCount(store.pending().size)

    private fun refuse(status: Status): Boolean {
        listener.onStatus(status)
        return false
    }

    private fun onMaxDuration(file: File) {
        // The recorder may call back on another thread; hop to ours, and ignore a late call for an old recording.
        scope.launch { if (recordingFile == file) stopAndTranscribe() }
    }

    private fun discardRecording(file: File) {
        recordingFile = null
        recorder.cancel()
        store.discard(file)
    }

    private fun enqueue(file: File, target: FieldIdentity?) {
        queuedFiles += file
        val job = scope.launch {
            try {
                sendLock.withLock {
                    try {
                        send(file, target)
                    } finally {
                        activeJobStatus = null
                    }
                }
            } finally {
                queuedFiles -= file
            }
        }
        sendJobs += job
        job.invokeOnCompletion { sendJobs -= job }
    }

    private suspend fun send(file: File, target: FieldIdentity?) {
        for (attempt in 1..retry.maxAttempts) {
            progress(if (attempt == 1) Status.Transcribing else Status.Retrying(attempt, retry.maxAttempts))
            val key = keys.apiKey() ?: return fail(Status.NoApiKey)
            val result = api.transcribe(file, key, requests.request())
            when (val failure = FailureClassifier.classify(result)) {
                null -> return succeed(file, (result as TranscriptionResult.Success).text, target)
                FailureClass.AuthRejected -> return fail(Status.AuthRejected)
                is FailureClass.Rejected -> return fail(Status.Rejected(failure.message))
                is FailureClass.Retryable -> {
                    if (!retry.hasAttemptsLeft(attempt)) break
                    cue(Cue.Retry)
                    if (retry.waitForConnectivityAfter(attempt)) {
                        progress(Status.WaitingForSignal)
                        gate.awaitOnline()
                    }
                    delay(retry.delayAfter(attempt, failure.retryAfterMs))
                }
            }
        }
        fail(Status.GaveUp(retry.maxAttempts))
    }

    private fun succeed(file: File, raw: String, target: FieldIdentity?) {
        activeJobStatus = null
        val text = TranscriptCleaner.clean(raw)
        if (TranscriptCleaner.isNothingHeard(text)) {
            store.discard(file)
            refreshPendingCount()
            cue(Cue.NothingHeard)
            outcome(Status.NothingHeard)
            return
        }
        store.lastText = text
        store.markSucceeded(file)
        refreshPendingCount()
        val current = delivery.currentField()
        if (target != null && current == target) {
            delivery.commit(withLeadingSpace(text))
            outcome(Status.Idle)
        } else {
            delivery.copyToClipboard(text)
            outcome(Status.TextReady)
        }
    }

    /** Stop sending this file; the audio stays pending. */
    private fun fail(status: Status) {
        activeJobStatus = null
        cue(Cue.Error)
        outcome(status)
    }

    private fun withLeadingSpace(text: String) =
        if (TranscriptCleaner.needsLeadingSpace(delivery.charBeforeCursor())) " $text" else text

    private fun progress(status: Status) {
        activeJobStatus = status
        if (!isRecording) listener.onStatus(status)
    }

    private fun outcome(status: Status) {
        if (!isRecording) listener.onStatus(status)
    }

    private fun cue(cue: Cue) {
        if (!isRecording) listener.onCue(cue)
    }
}
