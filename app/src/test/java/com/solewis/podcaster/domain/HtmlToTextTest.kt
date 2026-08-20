package com.solewis.podcaster.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HtmlToTextTest {

    @Test
    fun `strips basic tags`() {
        assertThat(HtmlToText.toPlainText("<p>Hello <strong>world</strong></p>"))
            .isEqualTo("Hello world")
    }

    @Test
    fun `strips list markup seen in real feed descriptions`() {
        // Real markup shape seen in feeds.simplecast.com/54nAGcIl item descriptions
        assertThat(HtmlToText.toPlainText("<ul><li>One</li><li>Two</li></ul>"))
            .isEqualTo("One Two")
    }

    @Test
    fun `decodes a single level of named entities`() {
        assertThat(HtmlToText.toPlainText("Bed &amp; breakfast")).isEqualTo("Bed & breakfast")
    }

    @Test
    fun `decodes double-encoded apostrophe entity`() {
        // Raw XML source contains the literal text "&amp;amp;#39;"; the XML parser's mandatory
        // single unescape turns that into "&amp;#39;" by the time it reaches this function.
        assertThat(HtmlToText.toPlainText("It&amp;#39;s here")).isEqualTo("It's here")
    }

    @Test
    fun `single-encoded apostrophe still resolves correctly`() {
        assertThat(HtmlToText.toPlainText("It&#39;s here")).isEqualTo("It's here")
    }

    @Test
    fun `decodes hex numeric entity`() {
        assertThat(HtmlToText.toPlainText("&#x27;quoted&#x27;")).isEqualTo("'quoted'")
    }

    @Test
    fun `collapses whitespace left behind by stripped tags`() {
        assertThat(HtmlToText.toPlainText("<p>One</p>\n<p>Two</p>")).isEqualTo("One Two")
    }

    @Test
    fun `null input returns null`() {
        assertThat(HtmlToText.toPlainText(null)).isNull()
    }

    @Test
    fun `blank input returns null`() {
        assertThat(HtmlToText.toPlainText("   ")).isNull()
    }

    @Test
    fun `tags-only input returns null after stripping`() {
        assertThat(HtmlToText.toPlainText("<br/><hr/>")).isNull()
    }

    @Test
    fun `plain text with no markup passes through unchanged`() {
        assertThat(HtmlToText.toPlainText("Just plain text.")).isEqualTo("Just plain text.")
    }
}
