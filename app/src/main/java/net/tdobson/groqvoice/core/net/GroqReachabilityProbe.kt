package net.tdobson.groqvoice.core.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.coroutines.cancellation.CancellationException

/** Desktop checkApiConnectivity(): HEAD api.groq.com with a 5 s timeout. Any HTTP answer means reachable. */
class GroqReachabilityProbe(
    private val http: OkHttpClient,
    private val url: HttpUrl = "https://api.groq.com/".toHttpUrl(),
    private val timeoutMs: Long = 5_000,
) {
    suspend fun isReachable(): Boolean = try {
        val call = http.newCall(Request.Builder().url(url).head().build())
        call.timeout().timeout(timeoutMs, MILLISECONDS)
        call.awaitResponse()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        false
    }
}
