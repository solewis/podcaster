package com.solewis.podcaster.ui.common

import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.text.HtmlCompat
import com.solewis.podcaster.domain.HtmlToText

/**
 * Renders an episode description as formatted text rather than the single collapsed blob
 * [HtmlToText.toPlainText] produces. Show notes lean heavily on paragraphs, links and emphasis -
 * timestamps, guest bios, sponsor URLs - and flattening all of it into one run of prose loses
 * most of what makes them readable.
 *
 * Uses the platform HTML parser (rather than extending the hand-rolled one in `domain/`) because
 * it already handles the messy real-world markup feeds contain; the tradeoff is that this can't
 * be unit-tested off-device, which is exactly why it lives here in `ui/` and not in the
 * deliberately Android-free `domain/` package.
 */
fun htmlToAnnotatedString(html: String, linkColor: Color): AnnotatedString? {
    if (html.isBlank()) return null

    // One unescape first: feeds are frequently double-encoded, so this strips the extra layer
    // and leaves a single well-formed one for the parser below to read as actual markup.
    val prepared = HtmlToText.unescapeOnce(html)
    // LEGACY, not COMPACT: the two differ only in how much space they leave between block
    // elements, and COMPACT gives paragraphs a single newline - which renders as a plain line
    // break, so a wall of show notes runs together with nothing marking where one paragraph ends
    // and the next begins. LEGACY leaves a blank line, which is what a paragraph should look like.
    // `<br>` is not a block element, so single line breaks stay single.
    val spanned = HtmlCompat.fromHtml(prepared, HtmlCompat.FROM_HTML_MODE_LEGACY)

    val full = buildAnnotatedString {
        append(spanned.toString())

        spanned.getSpans(0, spanned.length, Any::class.java).forEach { span ->
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            if (start !in 0..end || end > spanned.length) return@forEach

            when (span) {
                is StyleSpan -> when (span.style) {
                    android.graphics.Typeface.BOLD ->
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                    android.graphics.Typeface.ITALIC ->
                        addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                    android.graphics.Typeface.BOLD_ITALIC ->
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), start, end)
                }

                is UnderlineSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)

                is URLSpan -> addLink(
                    LinkAnnotation.Url(
                        url = span.url,
                        styles = TextLinkStyles(
                            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                        )
                    ),
                    start,
                    end
                )
            }
        }
    }

    // The parser leaves trailing newlines behind every closing block tag; trim via subSequence so
    // the span offsets shift with the text instead of pointing past the end of it.
    val start = full.indexOfFirst { !it.isWhitespace() }
    val end = full.indexOfLast { !it.isWhitespace() }
    if (start == -1) return null
    return full.subSequence(start, end + 1)
}

private fun AnnotatedString.indexOfFirst(predicate: (Char) -> Boolean): Int =
    text.indexOfFirst(predicate)

private fun AnnotatedString.indexOfLast(predicate: (Char) -> Boolean): Int =
    text.indexOfLast(predicate)

/** True when [Spanned] parsing produced nothing worth showing. */
internal fun Spanned.isEffectivelyEmpty(): Boolean = toString().isBlank()
