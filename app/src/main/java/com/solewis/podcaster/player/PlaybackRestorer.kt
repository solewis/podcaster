package com.solewis.podcaster.player

import com.solewis.podcaster.data.repo.EpisodeRepository

/**
 * Puts the last-played episode back in front of the user when the app starts.
 *
 * A separate class rather than two lines in `PodcasterApp` so the decision - which episode, and
 * whether there is one at all - is reachable from a test without standing up an `Application`.
 */
class PlaybackRestorer(
    private val episodeRepository: EpisodeRepository,
    private val playback: Playback
) {
    /** No-op on a fresh install, and on any launch where nothing has ever been played. */
    suspend fun restore() {
        val episode = episodeRepository.getLastPlayed() ?: return
        // Checked after the read, not before: the read suspends, and startup races the first
        // frame, so the user may have tapped an episode in the meantime. That live session owns
        // the playback state - restoring over it would put the wrong episode on screen.
        if (playback.state.value.episodeId != null) return
        playback.restore(episode)
    }
}
