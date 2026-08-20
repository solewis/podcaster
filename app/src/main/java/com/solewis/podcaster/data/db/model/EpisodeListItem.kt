package com.solewis.podcaster.data.db.model

/**
 * Lightweight projection for episode list screens - deliberately excludes `descriptionHtml`,
 * which can be several KB of HTML per row. A show with thousands of episodes must not hold that
 * much text live in a `StateFlow` just to render a list of titles and durations; the full
 * description is loaded separately, by id, only when an episode's detail sheet is opened.
 */
data class EpisodeListItem(
    val id: String,
    val podcastId: Long,
    val title: String,
    val pubDateMillis: Long?,
    val durationMillis: Long?,
    val displayNumber: Int?,
    val chronoIndex: Int?,
    val episodeType: String,
    val artworkUrl: String?,
    val positionMillis: Long,
    val isPlayed: Boolean,
    val lastPlayedAt: Long?
)
