package com.solewis.podcaster.data.remote

import kotlinx.serialization.Serializable

/**
 * DTOs for the iTunes Search API (`https://itunes.apple.com/search?media=podcast`). Verified
 * live: no API key or User-Agent is required, the response envelope always has this shape, and
 * `feedUrl` can legitimately be absent on some results - callers must filter those out, since
 * there is nothing to subscribe to without a feed URL. The real Content-Type header is
 * `text/javascript`, not `application/json` - irrelevant here since the body is decoded directly
 * with kotlinx.serialization rather than through a Content-Type-checking framework.
 */
@Serializable
data class ItunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<ItunesPodcastResult> = emptyList()
)

@Serializable
data class ItunesPodcastResult(
    val collectionId: Long? = null,
    val collectionName: String? = null,
    val artistName: String? = null,
    val feedUrl: String? = null,
    val artworkUrl600: String? = null,
    val artworkUrl100: String? = null,
    val trackCount: Int? = null,
    val primaryGenreName: String? = null
)
