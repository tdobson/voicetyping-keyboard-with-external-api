package net.tdobson.groqvoice.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptCleanerTest {
    /** raw to expected, each row checked against voiceTyping.js behaviour (trimStart, Thank-you tail, trimEnd). */
    private val parity = listOf(
        " Hello world." to "Hello world.",
        "Hello world. Thank you." to "Hello world.",
        "Hello. Thank you. Thank you." to "Hello.",
        "Hello. Thank You." to "Hello.",
        "Hello. thank you." to "Hello. thank you.",
        "Thank you for coming." to "Thank you for coming.",
        "Thank you. Hello." to "Thank you. Hello.",
        "Hello Thank you" to "Hello Thank you",
        "Thanks." to "Thanks.",
        "Hello\nThank you.\n" to "Hello",
        " Hi.  \n" to "Hi.",
        "Thank you." to "",
        "   " to "",
        // JavaScript \s and trim() are Unicode-aware: no-break space, BOM, ideographic space, line separator.
        "Hello.\u00a0Thank you." to "Hello.",
        "\ufeff\u00a0Hi.\u2003" to "Hi.",
        "Hi.\u3000Thank you.\u2028" to "Hi.",
    )

    @Test fun `cleanup matches desktop for every parity row`() {
        for ((raw, expected) in parity) {
            assertEquals("row ${raw.escaped()}", expected, TranscriptCleaner.clean(raw))
        }
    }

    @Test(timeout = 2_000) fun `many spaced thank-yous before other text do not backtrack forever`() {
        val raw = "Thank you.   ".repeat(40) + "and then more"
        assertEquals(raw.trimEnd(), TranscriptCleaner.clean(raw))
    }

    @Test fun `empty after cleaning means nothing heard`() = assertTrue(TranscriptCleaner.isNothingHeard(""))
    @Test fun `whitespace means nothing heard`() = assertTrue(TranscriptCleaner.isNothingHeard(" \n"))
    @Test fun `real text is heard`() = assertFalse(TranscriptCleaner.isNothingHeard("Hi"))

    @Test fun `no leading space at start of field`() = assertFalse(TranscriptCleaner.needsLeadingSpace(null))

    @Test fun `no leading space after whitespace`() {
        for (c in listOf(' ', '\n', '\t')) assertFalse("char ${c.code}", TranscriptCleaner.needsLeadingSpace(c))
    }

    @Test fun `leading space after a non-space character`() {
        for (c in listOf('a', '.', '(')) assertTrue("char $c", TranscriptCleaner.needsLeadingSpace(c))
    }

    private fun String.escaped() = "\"" + map { if (it.code in 32..126) "$it" else "\\u%04x".format(it.code) }.joinToString("") + "\""
}
