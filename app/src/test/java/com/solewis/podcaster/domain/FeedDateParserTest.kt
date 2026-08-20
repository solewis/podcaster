package com.solewis.podcaster.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class FeedDateParserTest {

    @Test
    fun `standard RFC-1123 with day name and numeric offset - real value from captured feeds`() {
        // Verified real format from both feeds.simplecast.com/54nAGcIl and lexfridman.com feed
        val millis = FeedDateParser.parseToEpochMillis("Wed, 19 Aug 2026 09:45:00 +0000")
        assertThat(millis).isEqualTo(Instant.parse("2026-08-19T09:45:00Z").toEpochMilli())
    }

    @Test
    fun `missing day-of-week name still parses`() {
        val millis = FeedDateParser.parseToEpochMillis("19 Aug 2026 09:45:00 +0000")
        assertThat(millis).isEqualTo(Instant.parse("2026-08-19T09:45:00Z").toEpochMilli())
    }

    @Test
    fun `missing seconds still parses`() {
        val millis = FeedDateParser.parseToEpochMillis("Wed, 19 Aug 2026 09:45 +0000")
        assertThat(millis).isEqualTo(Instant.parse("2026-08-19T09:45:00Z").toEpochMilli())
    }

    @Test
    fun `single digit day parses`() {
        val millis = FeedDateParser.parseToEpochMillis("Sun, 9 Aug 2026 09:45:00 +0000")
        assertThat(millis).isEqualTo(Instant.parse("2026-08-09T09:45:00Z").toEpochMilli())
    }

    @Test
    fun `literal GMT zone parses via RFC_1123_DATE_TIME`() {
        val millis = FeedDateParser.parseToEpochMillis("Wed, 19 Aug 2026 09:45:00 GMT")
        assertThat(millis).isEqualTo(Instant.parse("2026-08-19T09:45:00Z").toEpochMilli())
    }

    @Test
    fun `named zone EST parses with correct fixed offset`() {
        val millis = FeedDateParser.parseToEpochMillis("Wed, 19 Aug 2026 09:45:00 EST")
        // EST = UTC-5, so 09:45 EST is 14:45 UTC
        assertThat(millis).isEqualTo(Instant.parse("2026-08-19T14:45:00Z").toEpochMilli())
    }

    @Test
    fun `named zone PST parses with correct fixed offset`() {
        val millis = FeedDateParser.parseToEpochMillis("19 Aug 2026 09:45:00 PST")
        // PST = UTC-8
        assertThat(millis).isEqualTo(Instant.parse("2026-08-19T17:45:00Z").toEpochMilli())
    }

    @Test
    fun `ISO-8601 with Z suffix parses`() {
        val millis = FeedDateParser.parseToEpochMillis("2026-08-19T09:45:00Z")
        assertThat(millis).isEqualTo(Instant.parse("2026-08-19T09:45:00Z").toEpochMilli())
    }

    @Test
    fun `ISO-8601 with explicit offset parses`() {
        val millis = FeedDateParser.parseToEpochMillis("2026-08-19T09:45:00+02:00")
        assertThat(millis).isEqualTo(Instant.parse("2026-08-19T07:45:00Z").toEpochMilli())
    }

    @Test
    fun `ISO-8601 local date-time with no offset is assumed UTC`() {
        val millis = FeedDateParser.parseToEpochMillis("2026-08-19T09:45:00")
        assertThat(millis).isEqualTo(Instant.parse("2026-08-19T09:45:00Z").toEpochMilli())
    }

    @Test
    fun `null input returns null`() {
        assertThat(FeedDateParser.parseToEpochMillis(null)).isNull()
    }

    @Test
    fun `blank input returns null`() {
        assertThat(FeedDateParser.parseToEpochMillis("   ")).isNull()
    }

    @Test
    fun `garbage text returns null rather than throwing`() {
        assertThat(FeedDateParser.parseToEpochMillis("not a date at all")).isNull()
    }
}
