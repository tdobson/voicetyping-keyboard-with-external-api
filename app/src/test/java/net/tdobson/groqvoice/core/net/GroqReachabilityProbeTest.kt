package net.tdobson.groqvoice.core.net

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit.SECONDS

class GroqReachabilityProbeTest {
    private val server = MockWebServer()
    private lateinit var probe: GroqReachabilityProbe

    @Before fun setUp() {
        server.start()
        probe = GroqReachabilityProbe(OkHttpClient(), server.url("/"), timeoutMs = 300)
    }

    @After fun tearDown() = server.close()

    @Test fun `any http answer means reachable and the probe is a HEAD`() {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertTrue(runBlocking { probe.isReachable() })
        assertEquals("HEAD", server.takeRequest().method)
    }

    @Test fun `200 is reachable`() {
        server.enqueue(MockResponse.Builder().code(200).build())
        assertTrue(runBlocking { probe.isReachable() })
    }

    @Test fun `dropped connection is unreachable`() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
        assertFalse(runBlocking { probe.isReachable() })
    }

    @Test fun `no answer within the timeout is unreachable`() {
        server.enqueue(MockResponse.Builder().code(200).headersDelay(5, SECONDS).build())
        val t0 = System.nanoTime()
        assertFalse(runBlocking { probe.isReachable() })
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }
}
