package com.solewis.podcaster.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * Parses the `<pubDate>` value from a podcast RSS feed into epoch milliseconds.
 *
 * RSS specifies RFC-822/1123 dates, but real feeds are inconsistent: the day-of-week name is
 * sometimes missing, seconds are sometimes omitted, and the timezone is sometimes a named zone
 * (GMT, EST, PST...) rather than a numeric offset. A minority of feeds use ISO-8601 instead. This
 * tries a fixed list of formats in order and returns `null` rather than throwing - an unparseable
 * date degrades feed ordering to feed-position order (see [EpisodeNumbering]), it never crashes a
 * refresh. Two-digit years are deliberately not supported: RFC-1123 practice has been 4-digit for
 * decades and it was not observed in either real feed captured while building this.
 */
object FeedDateParser {

    private val NAMED_ZONE_OFFSET_HOURS = mapOf(
        "UT" to 0, "GMT" to 0, "UTC" to 0,
        "EST" to -5, "EDT" to -4,
        "CST" to -6, "CDT" to -5,
        "MST" to -7, "MDT" to -6,
        "PST" to -8, "PDT" to -7
    )

    // Handles named-zone dates that DateTimeFormatter.RFC_1123_DATE_TIME can't: it only accepts
    // a numeric offset or the literal text "GMT", not EST/PST/etc. The zone suffix is stripped
    // and applied separately in parseNamedZone() below.
    private val dateTimeWithoutZone = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .optionalStart().appendPattern("EEE, ").optionalEnd()
        .appendPattern("d MMM yyyy HH:mm")
        .optionalStart().appendPattern(":ss").optionalEnd()
        .toFormatter(Locale.US)
        .withResolverStyle(ResolverStyle.LENIENT)

    fun parseToEpochMillis(raw: String?): Long? {
        val value = raw?.trim()
        if (value.isNullOrEmpty()) return null

        return parseRfc1123(value)
            ?: parseNamedZone(value)
            ?: parseIsoOffset(value)
            ?: parseIsoLocalAsUtc(value)
            ?: parseIsoInstant(value)
    }

    private fun parseRfc1123(value: String): Long? = runCatching {
        OffsetDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()

    private fun parseNamedZone(value: String): Long? {
        val zoneName = NAMED_ZONE_OFFSET_HOURS.keys
            .sortedByDescending { it.length }
            .firstOrNull { value.endsWith(" $it", ignoreCase = true) }
            ?: return null
        val withoutZone = value.dropLast(zoneName.length).trim()
        val offsetHours = NAMED_ZONE_OFFSET_HOURS.getValue(zoneName)

        return runCatching {
            LocalDateTime.parse(withoutZone, dateTimeWithoutZone)
                .toInstant(ZoneOffset.ofHours(offsetHours))
                .toEpochMilli()
        }.getOrNull()
    }

    private fun parseIsoOffset(value: String): Long? = runCatching {
        OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()

    private fun parseIsoLocalAsUtc(value: String): Long? = runCatching {
        LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()

    private fun parseIsoInstant(value: String): Long? = runCatching {
        Instant.parse(value).toEpochMilli()
    }.getOrNull()
}
