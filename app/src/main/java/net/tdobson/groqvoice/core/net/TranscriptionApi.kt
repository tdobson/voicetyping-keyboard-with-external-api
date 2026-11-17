package net.tdobson.groqvoice.core.net

import java.io.File
import java.io.IOException

/** Form fields sent with every transcription, matching voiceTyping.js. */
data class TranscriptionRequest(
    val model: String = "whisper-large-v3-turbo",
    val language: String = "en",
    val temperature: String = "0",
    val responseFormat: String = "text",
)

/** Typed outcome of one transcription call. Never contains the API key. */
sealed interface TranscriptionResult {
    data class Success(val text: String) : TranscriptionResult
    data class HttpError(val code: Int, val message: String?, val retryAfterMs: Long?) : TranscriptionResult
    data class NetworkError(val cause: IOException) : TranscriptionResult
    data class UnexpectedError(val cause: Throwable) : TranscriptionResult
}

sealed interface KeyCheckResult {
    data object Valid : KeyCheckResult
    data object Rejected : KeyCheckResult
    data object NoConnection : KeyCheckResult
    data class Failed(val code: Int) : KeyCheckResult
}

interface TranscriptionApi {
    suspend fun transcribe(audio: File, apiKey: String, request: TranscriptionRequest): TranscriptionResult
}
