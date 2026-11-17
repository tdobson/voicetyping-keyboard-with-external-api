package net.tdobson.groqvoice.core.text

/** Transcript cleanup, ported from voiceTyping.js so phone and desktop produce the same text. */
object TranscriptCleaner {
    /**
     * The characters JavaScript treats as whitespace in `\s`, `trimStart()` and `trimEnd()`
     * (ECMAScript WhiteSpace plus LineTerminator). Java's `\s` and Kotlin's `trim` differ (ASCII-only, no BOM),
     * so the set is spelled out to keep exact parity.
     */
    private const val JS_SPACE = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"
    private val LEADING = Regex("^$JS_SPACE+")
    private val TRAILING = Regex("$JS_SPACE+\\z")

    /** voiceTyping.js: replace(/(\s*Thank [Yy]ou\.\s*)+$/g, ''). `\z` is JS `$` without the m flag. */
    private val THANK_YOU_TAIL = Regex("($JS_SPACE*Thank [Yy]ou\\.$JS_SPACE*)+\\z")

    /** Exactly voiceTyping.js: trimStart(), then strip trailing "Thank you." runs, then trimEnd(). */
    fun clean(raw: String): String {
        val start = raw.replace(LEADING, "")
        return start.replace(THANK_YOU_TAIL, "").replace(TRAILING, "")
    }

    /** Empty or whitespace after cleaning means Whisper heard nothing. */
    fun isNothingHeard(cleaned: String): Boolean = cleaned.isBlank()

    /** Leading space needed when the character before the cursor exists and is not whitespace. */
    fun needsLeadingSpace(charBeforeCursor: Char?): Boolean =
        charBeforeCursor != null && !charBeforeCursor.isWhitespace()
}
