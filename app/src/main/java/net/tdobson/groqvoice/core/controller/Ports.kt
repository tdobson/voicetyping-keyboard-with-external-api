package net.tdobson.groqvoice.core.controller

import net.tdobson.groqvoice.core.net.TranscriptionRequest
import java.io.File

/** Which text field a transcription belongs to: EditorInfo package + field id + a counter bumped on every onStartInput. */
data class FieldIdentity(val packageName: String, val fieldId: Int, val session: Long)

interface AudioRecorder {
    /** Starts recording into [file]. Returns false if the recorder could not start. */
    fun start(file: File, onMaxDuration: () -> Unit): Boolean
    /** Stops; true if a file was finalised. Never throws. */
    fun stop(): Boolean
    /** Stops and deletes the file. Never throws. */
    fun cancel()
}

interface RecordingStore {
    /** recording/<millis>.ogg */
    fun newRecordingFile(): File
    /** pending/<same name> */
    fun moveToPending(file: File): File
    /** Oldest first. */
    fun pending(): List<File>
    /** Exists and length > 1024. */
    fun isValid(file: File): Boolean
    /** Move to history, keep the last 2. */
    fun markSucceeded(file: File)
    fun discard(file: File)
    var lastText: String?
}

fun interface ApiKeyProvider { fun apiKey(): String? }

fun interface RequestProvider { fun request(): TranscriptionRequest }

fun interface MicPermission { fun granted(): Boolean }

interface ConnectivityGate {
    /** Suspends until the network is validated and Groq answers; cancellable. */
    suspend fun awaitOnline()
}

interface DeliveryTarget {
    fun currentField(): FieldIdentity?
    fun charBeforeCursor(): Char?
    fun commit(text: String)
    fun copyToClipboard(text: String)
}

interface ControllerListener {
    fun onStatus(status: Status)
    fun onPendingCount(count: Int)
    fun onCue(cue: Cue)
}

fun interface Clock { fun nowMs(): Long }
