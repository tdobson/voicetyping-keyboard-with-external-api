package net.tdobson.groqvoice.core.net

/** Pulls a human-readable message out of a Groq error body. A regex, because org.json is a stub in JVM tests. */
object GroqErrorMessage {
    private const val MAX_PLAIN_CHARS = 200
    private val MESSAGE = Regex(""""message"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val ESCAPE = Regex("""\\(u[0-9a-fA-F]{4}|.)""")

    /** error.message from an OpenAI-style JSON body; else the first 200 chars of the body; null if blank. */
    fun extract(body: String?): String? {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return null
        val fromJson = MESSAGE.find(text)?.groupValues?.get(1)?.let(::unescape)
        if (!fromJson.isNullOrBlank()) return fromJson
        return text.take(MAX_PLAIN_CHARS)
    }

    private fun unescape(s: String): String = ESCAPE.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.length == 5 -> e.substring(1).toInt(16).toChar().toString()
            e == "n" -> "\n"
            e == "t" -> "\t"
            e == "r" -> "\r"
            e == "b" -> "\b"
            e == "f" -> "\u000C"
            else -> e // \" \\ \/ and anything unknown: the character itself
        }
    }
}
