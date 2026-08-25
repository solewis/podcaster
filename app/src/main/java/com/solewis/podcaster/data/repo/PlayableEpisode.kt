package com.solewis.podcaster.data.repo

/** Exactly what the player needs to start an episode - deliberately not the full [com.solewis.podcaster.data.db.entity.EpisodeEntity]. */
data class PlayableEpisode(
    val episodeId: String,
    val title: String,
    val podcastTitle: String,
    val artworkUrl: String?,
    val mediaUrl: String,
    val startPositionMillis: Long,
    /**
     * The duration already known from the feed (or backfilled by a previous playback), so a
     * restored mini player can draw a real progress bar before any player has loaded the media and
     * reported its own. Null when the feed never gave one and the episode has never been played.
     */
    val durationMillis: Long? = null
)
