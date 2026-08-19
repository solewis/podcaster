package com.solewis.podcaster.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DurationParserTest {

    @Test
    fun `H MM SS format parses correctly - real value from NYT Daily feed`() {
        // Verified real value from feeds.simplecast.com/54nAGcIl
        assertThat(DurationParser.parseToMillis("00:23:17")).isEqualTo(23 * 60_000L + 17_000L)
    }

    @Test
    fun `H MM SS with nonzero hour parses correctly`() {
        assertThat(DurationParser.parseToMillis("01:02:03"))
            .isEqualTo((1 * 3600 + 2 * 60 + 3) * 1000L)
    }

    @Test
    fun `M SS format parses correctly - format seen in Lex Fridman feed`() {
        assertThat(DurationParser.parseToMillis("23:45")).isEqualTo((23 * 60 + 45) * 1000L)
    }

    @Test
    fun `single digit minute in M SS parses correctly`() {
        assertThat(DurationParser.parseToMillis("9:05")).isEqualTo((9 * 60 + 5) * 1000L)
    }

    @Test
    fun `raw whole seconds parses correctly`() {
        assertThat(DurationParser.parseToMillis("1425")).isEqualTo(1_425_000L)
    }

    @Test
    fun `raw decimal seconds parses correctly`() {
        assertThat(DurationParser.parseToMillis("1425.44")).isEqualTo(1_425_440L)
    }

    @Test
    fun `null input returns null - missing in 54 of 501 Lex Fridman episodes`() {
        assertThat(DurationParser.parseToMillis(null)).isNull()
    }

    @Test
    fun `empty string returns null`() {
        assertThat(DurationParser.parseToMillis("")).isNull()
    }

    @Test
    fun `blank string returns null`() {
        assertThat(DurationParser.parseToMillis("   ")).isNull()
    }

    @Test
    fun `junk text returns null rather than throwing`() {
        assertThat(DurationParser.parseToMillis("45 min")).isNull()
    }

    @Test
    fun `garbage colon value returns null rather than throwing`() {
        assertThat(DurationParser.parseToMillis("aa:bb")).isNull()
    }

    @Test
    fun `too many colon segments returns null`() {
        assertThat(DurationParser.parseToMillis("1:02:03:04")).isNull()
    }

    @Test
    fun `negative seconds returns null`() {
        assertThat(DurationParser.parseToMillis("-5")).isNull()
    }

    @Test
    fun `zero returns null - a zero length episode is not sane data`() {
        assertThat(DurationParser.parseToMillis("0")).isNull()
    }

    @Test
    fun `value beyond 24 hour sanity clamp returns null`() {
        assertThat(DurationParser.parseToMillis("100:00:00")).isNull()
    }

    @Test
    fun `value exactly at 24 hour clamp boundary is accepted`() {
        assertThat(DurationParser.parseToMillis("24:00:00")).isEqualTo(24 * 60 * 60 * 1000L)
    }
}
