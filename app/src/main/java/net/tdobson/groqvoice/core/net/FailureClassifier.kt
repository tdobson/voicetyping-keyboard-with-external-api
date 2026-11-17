package net.tdobson.groqvoice.core.net

import net.tdobson.groqvoice.core.net.TranscriptionResult.HttpError
import net.tdobson.groqvoice.core.net.TranscriptionResult.NetworkError
import net.tdobson.groqvoice.core.net.TranscriptionResult.Success
import net.tdobson.groqvoice.core.net.TranscriptionResult.UnexpectedError

sealed interface FailureClass {
    /** Try again, after [retryAfterMs] if the server said so, else the normal schedule. */
    data class Retryable(val retryAfterMs: Long?) : FailureClass
    /** 401/403: the key is wrong. Stop, keep the audio. */
    data object AuthRejected : FailureClass
    /** Groq refused this request (400, 413, other 4xx). Stop, keep the audio, show [message]. */
    data class Rejected(val message: String) : FailureClass
}

/** The spec's "Retry classification" table. */
object FailureClassifier {
    fun classify(result: TranscriptionResult): FailureClass? = when (result) {
        is Success -> null
        is NetworkError -> FailureClass.Retryable(null)
        is HttpError -> when {
            result.code == 429 || result.code in 500..599 -> FailureClass.Retryable(result.retryAfterMs)
            result.code == 401 || result.code == 403 -> FailureClass.AuthRejected
            else -> FailureClass.Rejected(result.message?.takeIf { it.isNotBlank() } ?: "HTTP ${result.code}")
        }
        // Exception text can contain anything (URLs, paths); the user sees a fixed message.
        is UnexpectedError -> FailureClass.Rejected("Unexpected error")
    }
}
