package com.solewis.podcaster.data.db.model

/**
 * An episode row for the cross-show "All Episodes" feed - like [EpisodeListItem] but joined
 * against its podcast, since a feed spanning every subscription needs to say *which show* each
 * episode belongs to. Kept as a separate projection rather than bolting podcast fields onto
 * [EpisodeListItem]: the single-show list already knows its podcast from the screen it's on and
 * has no use for these extra columns on every row.
 */
data class EpisodeFeedItem(
    val id: String,
    val podcastId: Long,
    val podcastTitle: String,
    val podcastArtworkUrl: String?,
    val title: String,
    val pubDateMillis: Long?,
    val durationMillis: Long?,
    val displayNumber: Int?,
    val episodeType: String,
    val artworkUrl: String?,
    val positionMillis: Long,
    val isPlayed: Boolean,
    val lastPlayedAt: Long?
)
