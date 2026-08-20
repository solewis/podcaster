package com.solewis.podcaster.data.remote

/**
 * Raw result of parsing an RSS document, before any app-specific interpretation (identity
 * resolution, numbering, date/duration parsing) is applied. Deliberately dumb: every field is
 * exactly what was in the XML, as a nullable string/raw value. Turning this into something the
 * app can use is the repository layer's job, using [com.solewis.podcaster.domain] functions.
 */
data class ParsedFeed(
    val channel: ParsedChannel,
    val items: List<ParsedItem>
)

data class ParsedChannel(
    val title: String? = null,
    val description: String? = null,
    val author: String? = null,
    val imageUrl: String? = null,
    val link: String? = null,
    val language: String? = null,
    /** `<itunes:type>` - "episodic" | "serial", or null if absent. */
    val itunesType: String? = null,
    /** `<itunes:new-feed-url>` - if present, the feed has moved and this URL should replace it. */
    val newFeedUrl: String? = null,
    /** `<atom:link rel="next">` - RFC 5005 paged feeds. Null if this is the only/last page. */
    val nextPageUrl: String? = null
)

data class ParsedItem(
    /** 0-based position in document order, oldest metadata we have before any date parsing. */
    val feedPosition: Int,
    val title: String? = null,
    val guid: String? = null,
    val guidIsPermaLink: Boolean = true,
    val enclosureUrl: String? = null,
    val enclosureBytes: Long? = null,
    val enclosureMimeType: String? = null,
    /** Raw `<pubDate>` text, unparsed - see [com.solewis.podcaster.domain.FeedDateParser]. */
    val pubDateRaw: String? = null,
    /** Raw `<itunes:duration>` text, unparsed - see [com.solewis.podcaster.domain.DurationParser]. */
    val durationRaw: String? = null,
    /** Richest available body: prefers `<content:encoded>`, falls back to `<description>`. */
    val descriptionHtml: String? = null,
    val imageUrl: String? = null,
    val itunesEpisodeNumber: Int? = null,
    val itunesSeason: Int? = null,
    /** `<itunes:episodeType>` - "full" | "trailer" | "bonus". Defaults to "full" when absent. */
    val episodeType: String = "full",
    val webPageUrl: String? = null
)
