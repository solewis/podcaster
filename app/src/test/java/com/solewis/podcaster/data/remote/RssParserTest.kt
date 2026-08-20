package com.solewis.podcaster.data.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class RssParserTest {

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("feeds/$name")) {
            "Missing test fixture: feeds/$name"
        }

    @Test
    fun `parses real NYT Daily feed slice - itunes duration, guid, content-encoded HTML`() {
        val feed = RssParser.parse(fixture("nyt_daily_slice.xml"))

        assertThat(feed.channel.title).isEqualTo("The Daily")
        assertThat(feed.items).hasSize(150)

        val first = feed.items.first()
        assertThat(first.guid).isNotEmpty()
        assertThat(first.durationRaw).matches("\\d{2}:\\d{2}:\\d{2}")
        assertThat(first.enclosureUrl).isNotEmpty()
        assertThat(first.episodeType).isEqualTo("full")
    }

    @Test
    fun `feed position is assigned in document order starting at zero`() {
        val feed = RssParser.parse(fixture("nyt_daily_slice.xml"))
        assertThat(feed.items.map { it.feedPosition }).isEqualTo((0 until feed.items.size).toList())
    }

    @Test
    fun `parses real Lex Fridman feed - handles missing durations without throwing`() {
        val feed = RssParser.parse(fixture("lex_fridman.xml"))

        assertThat(feed.items).hasSize(501)
        // Verified real fact: 54 of 501 items have no itunes:duration at all.
        val missingDurationCount = feed.items.count { it.durationRaw == null }
        assertThat(missingDurationCount).isEqualTo(54)
    }

    @Test
    fun `parses real Lex Fridman feed - mixed duration formats both come through as raw strings`() {
        val feed = RssParser.parse(fixture("lex_fridman.xml"))
        val formats = feed.items.mapNotNull { it.durationRaw }.map { raw ->
            when {
                Regex("\\d{1,2}:\\d{2}:\\d{2}").matches(raw) -> "H:MM:SS"
                Regex("\\d{1,2}:\\d{2}").matches(raw) -> "M:SS"
                else -> "other"
            }
        }.toSet()
        assertThat(formats).containsAtLeast("H:MM:SS", "M:SS")
    }

    @Test
    fun `duplicate guid feed still parses - identity resolution happens one layer up`() {
        val feed = RssParser.parse(fixture("duplicate_guids.xml"))
        assertThat(feed.items).hasSize(5)
        assertThat(feed.items.map { it.guid }.toSet()).containsExactly("static-guid-does-not-change")
    }

    @Test
    fun `serial feed with itunes episode numbers parses episode and season fields`() {
        val feed = RssParser.parse(fixture("serial_with_episode_numbers.xml"))
        assertThat(feed.channel.itunesType).isEqualTo("serial")
        assertThat(feed.items).hasSize(10)
        assertThat(feed.items.map { it.itunesEpisodeNumber }).isEqualTo((1..10).toList())
    }

    @Test
    fun `inconsistent episode numbering feed parses - some items missing itunes episode`() {
        val feed = RssParser.parse(fixture("inconsistent_episode_numbers.xml"))
        assertThat(feed.items).hasSize(3)
        assertThat(feed.items.map { it.itunesEpisodeNumber }).containsExactly(1, null, 1).inOrder()
    }

    @Test
    fun `rotating tracking token URLs parse to different raw enclosure strings`() {
        // The RSS parser itself does NOT normalize URLs - that's EpisodeIdentity's job. This
        // just confirms the raw value round-trips faithfully so identity resolution has
        // something real to work with.
        val v1 = RssParser.parse(fixture("rotating_token_v1.xml")).items.single().enclosureUrl
        val v2 = RssParser.parse(fixture("rotating_token_v2.xml")).items.single().enclosureUrl
        assertThat(v1).isNotEqualTo(v2)
        assertThat(v1).contains("dts.podtrac.com/redirect.mp3/")
    }

    @Test
    fun `malformed XML throws MalformedFeedException rather than a raw XML library exception`() {
        assertThrows(RssParser.MalformedFeedException::class.java) {
            RssParser.parse(fixture("malformed.xml"))
        }
    }

    @Test
    fun `feed whose root is not rss throws MalformedFeedException`() {
        // Real captured response: a 404 error body that still starts with an XML prolog, so a
        // naive "does it start with <?xml" check would not catch it - the root element itself
        // must be validated.
        assertThrows(RssParser.MalformedFeedException::class.java) {
            RssParser.parse(fixture("soft_404_error_page.xml"))
        }
    }

    @Test
    fun `synthetic 4000-item feed parses completely and stays in document order`() {
        // Stress test for the streaming parser at real-world-huge scale (the real NYT Daily feed
        // was 18.5 MB with 2952 items) without committing an 18 MB fixture to git.
        val feed = RssParser.parse(fixture("synthetic_stress_4000_items.xml"))
        assertThat(feed.items).hasSize(4000)
        assertThat(feed.items.first().title).isEqualTo("Stress Episode 1")
        assertThat(feed.items.last().title).isEqualTo("Stress Episode 4000")
    }

    @Test
    fun `content is captured as raw text - entity decoding and tag stripping happen in HtmlToText, not here`() {
        val feed = RssParser.parse(fixture("synthetic_stress_4000_items.xml"))
        val description = feed.items.first().descriptionHtml

        // Fixture source text is "...some &amp;amp; double-encoded... and &lt;b&gt;html&lt;/b&gt;.".
        // A SAX parser performs exactly one mandatory XML unescape pass while parsing, so by the
        // time it reaches us: "&amp;amp;" -> "&amp;" (still entity-encoded once - a second decode
        // pass is HtmlToText's job, not this parser's), while "&lt;b&gt;" -> "<b>" (that one was
        // only single-encoded, so it's now a literal, un-stripped tag - stripping is also
        // HtmlToText's job).
        assertThat(description).contains("&amp;")
        assertThat(description).contains("<b>html</b>")
    }
}
