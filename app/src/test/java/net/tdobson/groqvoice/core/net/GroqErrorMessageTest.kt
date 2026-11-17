package net.tdobson.groqvoice.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroqErrorMessageTest {
    @Test fun `openai style json gives error message`() = assertEquals(
        "Invalid API Key",
        GroqErrorMessage.extract("""{"error":{"message":"Invalid API Key","type":"invalid_request_error"}}"""),
    )

    @Test fun `escaped characters inside the message are unescaped`() = assertEquals(
        "file \"a.ogg\" is bad\\ok\né",
        GroqErrorMessage.extract("""{"error":{"message" : "file \"a.ogg\" is bad\\ok\n\u00e9"}}"""),
    )

    @Test fun `plain text body gives itself`() = assertEquals("Bad gateway", GroqErrorMessage.extract("Bad gateway"))
    @Test fun `plain text body is trimmed`() = assertEquals("Bad gateway", GroqErrorMessage.extract("  Bad gateway\n"))
    @Test fun `long plain body is cut to 200 chars`() = assertEquals("y".repeat(200), GroqErrorMessage.extract("y".repeat(500)))
    @Test fun `null body`() = assertNull(GroqErrorMessage.extract(null))
    @Test fun `empty body`() = assertNull(GroqErrorMessage.extract(""))
    @Test fun `blank body`() = assertNull(GroqErrorMessage.extract("  "))
    @Test fun `json with blank message falls back to the body`() =
        assertEquals("""{"error":{"message":""}}""", GroqErrorMessage.extract("""{"error":{"message":""}}"""))
}
