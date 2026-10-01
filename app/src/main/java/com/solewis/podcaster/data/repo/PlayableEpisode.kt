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
    val durationMillis: Long? = null,
    /**
     * The duration the *player* measured for the copy of this episode that [startPositionMillis]
     * was saved against - null when nothing has measured it, as opposed to the feed's estimate.
     *
     * Exists because a saved position only means something relative to one particular copy of the
     * file. Hosts that insert ads per request hand out copies of different lengths, so the same
     * millisecond can be minutes away from the same moment in the show - reported as an episode
     * with 4.7 minutes left being skipped outright, its resume position having landed past the end
     * of a copy 5 minutes shorter. See [com.solewis.podcaster.player.PositionRemapper].
     */
    val recordedDurationMillis: Long? = null
)
