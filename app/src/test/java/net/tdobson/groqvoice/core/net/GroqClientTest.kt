package net.tdobson.groqvoice.core.net

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import net.tdobson.groqvoice.core.net.TranscriptionResult.HttpError
import net.tdobson.groqvoice.core.net.TranscriptionResult.NetworkError
import net.tdobson.groqvoice.core.net.TranscriptionResult.Success
import net.tdobson.groqvoice.core.net.TranscriptionResult.UnexpectedError
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartReader
import okio.Buffer
import okio.BufferedSink
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit.SECONDS

/** Layer 2: GroqClient over real localhost sockets against MockWebServer. */
class GroqClientTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val fastTimeouts = TimeoutPolicy(connectTimeoutMs = 1_000, baseCallTimeoutMs = 1_500, inactivityTimeoutMs = 700)
    private lateinit var client: GroqClient
    private lateinit var audio: File

    @Before fun setUp() {
        server.start()
        client = client(fastTimeouts)
        audio = tmp.newFile("rec.ogg").apply { writeBytes(ByteArray(4096) { (it * 31 % 251).toByte() }) }
    }

    @After fun tearDown() = server.close()

    private fun client(t: TimeoutPolicy, nowMs: () -> Long = System::currentTimeMillis) =
        GroqClient(GroqClient.defaultHttpClient(t), server.url("/openai/v1/"), t, nowMs)

    private fun transcribe(c: GroqClient = client, request: TranscriptionRequest = TranscriptionRequest()) =
        runBlocking { c.transcribe(audio, KEY, request) }

    private class Part(val headers: okhttp3.Headers, val bytes: ByteArray) {
        val text get() = String(bytes)
        val contentDisposition get() = headers["Content-Disposition"].orEmpty()
        val contentType get() = headers["Content-Type"]
    }

    private fun readParts(body: ByteString, boundary: String): Map<String, Part> {
        val parts = mutableMapOf<String, Part>()
        MultipartReader(Buffer().write(body), boundary).use { reader ->
            while (true) {
                val part = reader.nextPart() ?: break
                val name = Regex("""name="([^"]*)"""").find(part.headers["Content-Disposition"]!!)!!.groupValues[1]
                parts[name] = Part(part.headers, part.body.readByteArray())
            }
        }
        return parts
    }

    @Test fun `200 returns raw text`() {
        server.enqueue(MockResponse.Builder().code(200).body(" Hello there.\n").build())
        assertEquals(Success(" Hello there.\n"), transcribe())
    }

    @Test fun `multipart shape matches desktop request`() {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        transcribe()
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/openai/v1/audio/transcriptions", req.url.encodedPath)
        assertEquals("Bearer $KEY", req.headers["Authorization"])
        val contentType = req.headers["Content-Type"]!!.toMediaType()
        assertEquals("multipart", contentType.type)
        assertEquals("form-data", contentType.subtype)
        val parts = readParts(req.body!!, contentType.parameter("boundary")!!)
        assertEquals(setOf("file", "model", "language", "temperature", "response_format"), parts.keys)
        assertEquals("whisper-large-v3-turbo", parts["model"]!!.text)
        assertEquals("en", parts["language"]!!.text)
        assertEquals("0", parts["temperature"]!!.text)
        assertEquals("text", parts["response_format"]!!.text)
        assertTrue(parts["file"]!!.contentDisposition.contains("filename=\"audio.ogg\""))
        assertEquals("audio/ogg", parts["file"]!!.contentType)
        assertArrayEquals(audio.readBytes(), parts["file"]!!.bytes)
    }

    @Test fun `language and model come from the request`() {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        transcribe(request = TranscriptionRequest(model = "whisper-large-v3", language = "de"))
        val req = server.takeRequest()
        val parts = readParts(req.body!!, req.headers["Content-Type"]!!.toMediaType().parameter("boundary")!!)
        assertEquals("whisper-large-v3", parts["model"]!!.text)
        assertEquals("de", parts["language"]!!.text)
    }

    @Test fun `401 gives http error with groq message and no retry`() {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"message":"Invalid API Key"}}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("should not be fetched").build())
        assertEquals(HttpError(401, "Invalid API Key", null), transcribe())
        assertEquals(1, server.requestCount)
    }

    @Test fun `429 carries retry-after in milliseconds`() {
        server.enqueue(MockResponse.Builder().code(429).addHeader("retry-after", "7").body("""{"error":{"message":"Rate limit"}}""").build())
        val r = transcribe() as HttpError
        assertEquals(429, r.code)
        assertEquals(7_000L, r.retryAfterMs)
    }

    @Test fun `429 retry-after as http date uses the injected clock`() {
        val now = 1_763_370_000_000L
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "Mon, 17 Nov 2025 09:00:10 GMT").build())
        val r = transcribe(client(fastTimeouts) { now }) as HttpError
        // 2025-11-17T09:00:10Z is 1_763_370_010_000
        assertEquals(10_000L, r.retryAfterMs)
    }

    @Test fun `500 gives http error`() {
        server.enqueue(MockResponse.Builder().code(500).body("Internal").build())
        assertEquals(HttpError(500, "Internal", null), transcribe())
    }

    @Test fun `stalled body hits the inactivity timeout quickly`() {
        server.enqueue(MockResponse.Builder().code(200).body("late").bodyDelay(5, SECONDS).build())
        val t0 = System.nanoTime()
        val r = transcribe()
        assertTrue("got $r", r is NetworkError)
        assertTrue(elapsedMs(t0) < 3_000)
    }

    @Test fun `trickling body is cut off by the call timeout`() {
        // 1 byte every 300 ms never trips the 700 ms inactivity timeout; only the 1.5 s call timeout can stop it.
        server.enqueue(MockResponse.Builder().code(200).body(Trickle("x".repeat(20), periodMs = 300)).build())
        val t0 = System.nanoTime()
        val r = transcribe()
        assertTrue("got $r", r is NetworkError)
        val ms = elapsedMs(t0)
        assertTrue("took $ms ms", ms in 1_400..3_000)
    }

    @Test fun `slow but progressing body is not cut off by inactivity timeouts`() {
        val t = TimeoutPolicy(connectTimeoutMs = 1_000, baseCallTimeoutMs = 5_000, inactivityTimeoutMs = 700)
        server.enqueue(MockResponse.Builder().code(200).body(Trickle("x".repeat(10), periodMs = 100)).build())
        assertEquals(Success("x".repeat(10)), transcribe(client(t)))
    }

    @Test fun `call timeout grows with the file size`() {
        // base 1 s, but 4096 bytes at 2048 B/s adds 2 s: a 1.5 s response must succeed.
        val t = TimeoutPolicy(connectTimeoutMs = 1_000, baseCallTimeoutMs = 1_000, uploadBytesPerSecond = 2_048, inactivityTimeoutMs = 700)
        server.enqueue(MockResponse.Builder().code(200).body(Trickle("y".repeat(10), periodMs = 150)).build())
        assertEquals(Success("y".repeat(10)), transcribe(client(t)))
    }

    @Test fun `dropped connection gives network error`() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
        val r = transcribe()
        assertTrue("got $r", r is NetworkError)
    }

    @Test fun `server gone gives network error`() {
        server.close()
        val r = transcribe()
        assertTrue("got $r", r is NetworkError)
    }

    @Test fun `missing audio file is unexpected rather than a retryable network error`() {
        audio.delete()
        val r = transcribe()
        assertTrue("got $r", r is UnexpectedError)
        assertEquals(0, server.requestCount)
    }

    @Test fun `cancelling the coroutine cancels the call promptly`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("late").headersDelay(10, SECONDS).build())
        val slow = client(TimeoutPolicy(baseCallTimeoutMs = 20_000, inactivityTimeoutMs = 20_000))
        val job = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { slow.transcribe(audio, KEY, TranscriptionRequest()) }
        delay(200)
        val t0 = System.nanoTime()
        job.cancel()
        withTimeout(2_000) { job.join() }
        assertTrue(job.isCancelled)
        assertTrue(elapsedMs(t0) < 2_000)
    }

    @Test fun `the key never appears in any result`() {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"message":"Invalid API Key"}}""").build())
        server.enqueue(MockResponse.Builder().code(500).body("oops").build())
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
        server.enqueue(MockResponse.Builder().code(200).body("late").bodyDelay(5, SECONDS).build())
        repeat(4) {
            val r = transcribe()
            assertFalse("result $it leaked the key: $r", r.toString().contains(KEY))
        }
    }

    @Test fun `a key with a newline is refused without echoing it`() {
        val bad = "$KEY\nX-Evil: 1"
        val r = runBlocking { client.transcribe(audio, bad, TranscriptionRequest()) }
        assertTrue("got $r", r is UnexpectedError)
        assertFalse(r.toString().contains(KEY))
        assertEquals(0, server.requestCount)
    }

    @Test fun `checkKey 200 is valid and is a GET of models with the bearer header`() {
        server.enqueue(MockResponse.Builder().code(200).body("""{"data":[]}""").build())
        assertEquals(KeyCheckResult.Valid, runBlocking { client.checkKey(KEY) })
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/openai/v1/models", req.url.encodedPath)
        assertEquals("Bearer $KEY", req.headers["Authorization"])
    }

    @Test fun `checkKey 401 is rejected`() {
        server.enqueue(MockResponse.Builder().code(401).build())
        assertEquals(KeyCheckResult.Rejected, runBlocking { client.checkKey(KEY) })
    }

    @Test fun `checkKey 403 is rejected`() {
        server.enqueue(MockResponse.Builder().code(403).build())
        assertEquals(KeyCheckResult.Rejected, runBlocking { client.checkKey(KEY) })
    }

    @Test fun `checkKey dropped connection is no connection`() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
        assertEquals(KeyCheckResult.NoConnection, runBlocking { client.checkKey(KEY) })
    }

    @Test fun `checkKey 500 is failed with the code`() {
        server.enqueue(MockResponse.Builder().code(500).build())
        assertEquals(KeyCheckResult.Failed(500), runBlocking { client.checkKey(KEY) })
    }

    /**
     * Response body that writes one byte per [periodMs]. (MockWebServer's throttleBody also throttles reading the
     * request body, which would stall the upload instead of the response.)
     */
    private class Trickle(private val text: String, private val periodMs: Long) : MockResponseBody {
        override val contentLength get() = text.length.toLong()
        override fun writeTo(sink: BufferedSink) {
            for (c in text) {
                Thread.sleep(periodMs)
                sink.writeUtf8(c.toString()).flush()
            }
        }
    }

    private fun elapsedMs(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    private companion object { const val KEY = "test-key-123" }
}
