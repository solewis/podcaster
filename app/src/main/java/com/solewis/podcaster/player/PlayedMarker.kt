package com.solewis.podcaster.player

import com.solewis.podcaster.data.repo.EpisodeRepository

/**
 * Marking an episode played or unplayed by hand, from wherever the action is offered - the episode
 * screen, a row in a show, a row in the Home feed.
 *
 * A class of its own rather than three copies in three ViewModels, because the interesting half is
 * not the database write. When the episode happens to be the one loaded in the player,
 * [ProgressWriter] re-derives `isPlayed` from the *live player position* every five seconds, so the
 * mark would quietly revert within seconds and nothing stored in the database can prevent it.
 * Seeking to the end is what makes every subsequent write agree with the mark - and it is what
 * "I'm done with this one" means anyway, since it also ends the episode and lets auto-advance carry
 * on.
 *
 * That only holds if the seek actually reaches the end, which is why the player's own duration is
 * preferred over the caller's below.
 */
class PlayedMarker(
    private val episodeRepository: EpisodeRepository,
    private val playback: Playback
) {

    /**
     * [durationMillis] is what the caller already knows from the row or screen it is on. Null means
     * unknown - the feed never said and the player has not reported one - in which case the mark is
     * still written and only the seek is skipped.
     */
    suspend fun setPlayed(episodeId: String, played: Boolean, durationMillis: Long?) {
        if (!played) {
            // No seek on the way back: nothing is going to overwrite an unplayed flag, since that is
            // what ProgressWriter would derive from a mid-episode position anyway.
            episodeRepository.markUnplayed(episodeId)
            return
        }
        episodeRepository.markPlayed(episodeId)
        // Loaded, not necessarily playing: a paused episode is still the one the writer reports on.
        if (playback.state.value.episodeId != episodeId) return
        // The player's own duration in preference to the caller's, which comes from the feed's
        // <itunes:duration> and is routinely short by more than [CompletionRule]'s threshold.
        // Seeking to a feed duration that undershoots the real one leaves playback short of the
        // end, so the episode never reaches STATE_ENDED and the next write derives "not complete"
        // - reverting the very mark this is meant to make stick.
        val target = playback.progress.value.durationMillis ?: durationMillis ?: return
        playback.seekTo(target)
    }
}
