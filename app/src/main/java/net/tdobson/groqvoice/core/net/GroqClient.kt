package net.tdobson.groqvoice.core.net

import net.tdobson.groqvoice.core.retry.RetryAfter
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.coroutines.cancellation.CancellationException

/**
 * Groq Whisper over OkHttp, the app's only network code. One call per [transcribe]: retrying is the controller's job.
 * Results never contain the API key (OkHttp redacts the Authorization header from its own messages).
 */
class GroqClient(
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl = "https://api.groq.com/openai/v1/".toHttpUrl(),
    private val timeouts: TimeoutPolicy = TimeoutPolicy(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : TranscriptionApi {

    /**
     * Same pool and timeouts, but no OkHttp-level retry: a dropped connection would otherwise re-send the whole
     * upload behind the controller's back, outside its 10-attempt budget and the "Retrying (n/10)" status.
     */
    private val uploads: OkHttpClient = http.newBuilder().retryOnConnectionFailure(false).build()

    override suspend fun transcribe(audio: File, apiKey: String, request: TranscriptionRequest): TranscriptionResult {
        if (!audio.isFile) return TranscriptionResult.UnexpectedError(FileNotFoundException("recording is missing"))
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.ogg", audio.asRequestBody(OGG))
                .addFormDataPart("model", request.model)
                .addFormDataPart("language", request.language)
                .addFormDataPart("temperature", request.temperature)
                .addFormDataPart("response_format", request.responseFormat)
                .build()
            val call = uploads.newCall(
                Request.Builder()
                    .url(baseUrl.resolve("audio/transcriptions")!!)
                    .header("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build(),
            )
            call.timeout().timeout(timeouts.callTimeoutMs(audio.length()), MILLISECONDS)
            val (response, text) = call.awaitResponse()
            if (response.isSuccessful) {
                TranscriptionResult.Success(text)
            } else {
                TranscriptionResult.HttpError(
                    code = response.code,
                    message = GroqErrorMessage.extract(text),
                    retryAfterMs = RetryAfter.parseMs(response.header("retry-after"), nowMs()),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            TranscriptionResult.NetworkError(e)
        } catch (e: Exception) {
            TranscriptionResult.UnexpectedError(e)
        }
    }

    /** GET {baseUrl}models: the cheapest authenticated call, used by the settings "Test key" button. */
    suspend fun checkKey(apiKey: String): KeyCheckResult = try {
        val call = http.newCall(
            Request.Builder()
                .url(baseUrl.resolve("models")!!)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build(),
        )
        call.timeout().timeout(timeouts.baseCallTimeoutMs, MILLISECONDS)
        val (response, _) = call.awaitResponse()
        when {
            response.isSuccessful -> KeyCheckResult.Valid
            response.code == 401 || response.code == 403 -> KeyCheckResult.Rejected
            else -> KeyCheckResult.Failed(response.code)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        KeyCheckResult.NoConnection
    } catch (_: Exception) {
        KeyCheckResult.Failed(0)
    }

    companion object {
        private val OGG = "audio/ogg".toMediaType()

        /** Connect timeout plus read/write inactivity timeouts; the total call timeout is set per call. */
        fun defaultHttpClient(t: TimeoutPolicy = TimeoutPolicy()): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(t.connectTimeoutMs, MILLISECONDS)
            .readTimeout(t.inactivityTimeoutMs, MILLISECONDS)
            .writeTimeout(t.inactivityTimeoutMs, MILLISECONDS)
            .build()
    }
}
