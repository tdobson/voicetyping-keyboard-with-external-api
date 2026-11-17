package net.tdobson.groqvoice.core.net

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Layer 2 placeholder: proves OkHttp talks to a MockWebServer over a real localhost socket. */
class MockWebServerHarnessTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    @Test
    fun getReturnsEnqueuedBody() {
        server.enqueue(MockResponse.Builder().code(200).body("hi").build())

        val body = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build()).execute().use {
            it.body.string()
        }

        assertEquals("hi", body)
        assertEquals("/", server.takeRequest().url.encodedPath)
    }
}
