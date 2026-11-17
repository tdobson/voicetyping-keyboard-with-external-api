package net.tdobson.groqvoice.core.net

import net.tdobson.groqvoice.core.net.FailureClass.AuthRejected
import net.tdobson.groqvoice.core.net.FailureClass.Rejected
import net.tdobson.groqvoice.core.net.FailureClass.Retryable
import net.tdobson.groqvoice.core.net.TranscriptionResult.HttpError
import net.tdobson.groqvoice.core.net.TranscriptionResult.NetworkError
import net.tdobson.groqvoice.core.net.TranscriptionResult.Success
import net.tdobson.groqvoice.core.net.TranscriptionResult.UnexpectedError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** The spec's "Retry classification" table, one test per row. */
class FailureClassifierTest {
    private fun classify(r: TranscriptionResult) = FailureClassifier.classify(r)

    @Test fun `success is not a failure`() = assertNull(classify(Success("x")))
    @Test fun `socket timeout retries`() = assertEquals(Retryable(null), classify(NetworkError(SocketTimeoutException())))
    @Test fun `dns failure retries`() = assertEquals(Retryable(null), classify(NetworkError(UnknownHostException())))
    @Test fun `connection refused retries`() = assertEquals(Retryable(null), classify(NetworkError(ConnectException())))
    @Test fun `okhttp call timeout retries`() =
        assertEquals(Retryable(null), classify(NetworkError(InterruptedIOException("timeout"))))

    @Test fun `5xx retries`() {
        for (code in listOf(500, 502, 503, 504)) assertEquals("HTTP $code", Retryable(null), classify(HttpError(code, "x", null)))
    }

    @Test fun `429 retries with retry-after`() = assertEquals(Retryable(3_000), classify(HttpError(429, "slow down", 3_000)))
    @Test fun `429 retries without retry-after`() = assertEquals(Retryable(null), classify(HttpError(429, "slow down", null)))
    @Test fun `retry-after on a 5xx is honoured too`() = assertEquals(Retryable(2_000), classify(HttpError(503, null, 2_000)))
    @Test fun `401 is an auth rejection`() = assertEquals(AuthRejected, classify(HttpError(401, "Invalid API Key", null)))
    @Test fun `403 is an auth rejection`() = assertEquals(AuthRejected, classify(HttpError(403, null, null)))
    @Test fun `400 shows groq message`() = assertEquals(Rejected("file too short"), classify(HttpError(400, "file too short", null)))
    @Test fun `413 without message shows the code`() = assertEquals(Rejected("HTTP 413"), classify(HttpError(413, null, null)))
    @Test fun `404 is rejected`() = assertEquals(Rejected("not found"), classify(HttpError(404, "not found", null)))
    @Test fun `blank message falls back to the code`() = assertEquals(Rejected("HTTP 422"), classify(HttpError(422, "  ", null)))

    @Test fun `unexpected error is rejected without exception text`() =
        assertEquals(Rejected("Unexpected error"), classify(UnexpectedError(IllegalStateException("boom"))))
}
