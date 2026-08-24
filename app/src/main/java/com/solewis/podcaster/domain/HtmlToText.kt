package com.solewis.podcaster.domain

/**
 * Converts an episode description (raw HTML/entity soup from RSS) into a display-plain string.
 *
 * Feed content is frequently double HTML-entity-encoded - a publishing tool escapes text that
 * was already escaped upstream (e.g. `&amp;amp;#39;` in the raw XML, which is `&amp;#39;` after
 * the one mandatory unescape the XML parser itself performs) - so entities are decoded twice
 * here before tags are stripped. Implemented as plain string manipulation rather than
 * `android.text.Html.fromHtml`, which is an Android framework call that can't run in a plain JVM
 * unit test without Robolectric; this way the whole parsing pipeline stays testable on the JVM.
 */
object HtmlToText {

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”"
    )

    private val ENTITY_REGEX = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")
    private val TAG_REGEX = Regex("<[^>]*>")
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun toPlainText(html: String?): String? {
        if (html.isNullOrBlank()) return null

        val decodedTwice = decodeEntities(decodeEntities(html))
        val withoutTags = TAG_REGEX.replace(decodedTwice, " ")
        val collapsed = WHITESPACE_REGEX.replace(withoutTags, " ").trim()
        return collapsed.takeIf { it.isNotEmpty() }
    }

    /**
     * A single unescape pass, exposed for callers that hand the result to a real HTML parser
     * afterwards (see `ui.common.htmlToAnnotatedString`). Feed content is commonly
     * double-encoded, so stripping one layer here leaves exactly one well-formed layer for the
     * parser to interpret as markup - matching the two passes [toPlainText] does internally.
     */
    fun unescapeOnce(html: String): String = decodeEntities(html)

    private fun decodeEntities(input: String): String =
        ENTITY_REGEX.replace(input) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", ignoreCase = true) ->
                    body.substring(2).toIntOrNull(16)?.let(::codePointToString) ?: match.value

                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.let(::codePointToString) ?: match.value

                else -> NAMED_ENTITIES[body] ?: match.value
            }
        }

    private fun codePointToString(codePoint: Int): String =
        runCatching { String(Character.toChars(codePoint)) }.getOrDefault("")
}
