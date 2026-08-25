package com.solewis.podcaster.data.db.model

/**
 * Everything the episode detail screen shows, joined against its podcast for the show name and
 * fallback artwork. Unlike [EpisodeListItem]/[EpisodeFeedItem] this deliberately *does* carry
 * `descriptionHtml`: those are list projections that omit it precisely because it can be several
 * KB per row, but a detail screen holds exactly one episode and the description is the point.
 */
data class EpisodeDetailItem(
    val id: String,
    val podcastId: Long,
    val podcastTitle: String,
    val podcastArtworkUrl: String?,
    val title: String,
    val descriptionHtml: String?,
    val pubDateMillis: Long?,
    val durationMillis: Long?,
    val displayNumber: Int?,
    val episodeType: String,
    val artworkUrl: String?,
    val positionMillis: Long,
    val isPlayed: Boolean
)
