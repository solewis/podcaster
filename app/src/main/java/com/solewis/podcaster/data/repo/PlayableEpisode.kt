package com.solewis.podcaster.data.repo

/** Exactly what the player needs to start an episode - deliberately not the full [com.solewis.podcaster.data.db.entity.EpisodeEntity]. */
data class PlayableEpisode(
    val episodeId: String,
    val title: String,
    val podcastTitle: String,
    val artworkUrl: String?,
    val mediaUrl: String
)
