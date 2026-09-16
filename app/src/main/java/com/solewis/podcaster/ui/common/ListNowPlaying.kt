package com.solewis.podcaster.ui.common

import com.solewis.podcaster.player.Playback
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * What a list row needs to know about whatever is currently loaded in the player: which episode it
 * is, and how far into it we are.
 *
 * Deliberately separate from the screen's own `UiState`. Folding the live position into that state
 * made every progress tick a new state object, which recomposed the entire feed - the same mistake
 * the download states were already kept out of `UiState` to avoid.
 */
data class ListNowPlaying(
    /**
     * Null when nothing is loaded, or when playback is not *intended* to be running.
     *
     * Intent (`playWhenReady`) rather than audibility (`isPlaying`): this drives the row's
     * play/pause icon and whether the row follows the live position, and a seek briefly makes
     * `isPlaying` false - which flicked the icon and dropped the row back to its stored position
     * mid-drag.
     */
    val episodeId: String? = null,
    /** Null while nothing is loaded, so a row can tell "paused at the start" from "not loaded". */
    val loadedEpisodeId: String? = null,
    val positionMillis: Long = 0,
    val durationMillis: Long? = null
) {
    /** True when this episode is the one in the player, playing or paused. */
    fun isActive(episodeId: String): Boolean = episodeId == loadedEpisodeId

    /** True only while that episode is actually meant to be making sound. */
    fun isPlaying(episodeId: String): Boolean = episodeId == this.episodeId
}

/**
 * The player's position, coarsened to something a *list* can actually show.
 *
 * The player publishes a position once per displayed second so the Now Playing screen's clock
 * ticks evenly. A list row has no clock: it shows whole minutes remaining and a progress bar a few
 * hundred pixels wide, where one pixel of an hour-long episode is the better part of a minute. So
 * a row cannot render a one-second change at all, and every one of those emissions was recomposing
 * rows to redraw an identical frame.
 *
 * Rounded rather than sampled, and paired with [distinctUntilChanged], so the flow emits only when
 * something a row can display has genuinely changed.
 */
fun Playback.listNowPlaying(): Flow<ListNowPlaying> =
    combine(state, progress) { playbackState, progress ->
        ListNowPlaying(
            episodeId = playbackState.episodeId.takeIf { playbackState.playWhenReady },
            loadedEpisodeId = playbackState.episodeId,
            positionMillis = progress.positionMillis - (progress.positionMillis % LIST_POSITION_STEP_MILLIS),
            durationMillis = progress.durationMillis
        )
    }.distinctUntilChanged()

/**
 * How finely a list row tracks the live position.
 *
 * Five seconds is below what a row can draw - the minutes-remaining label is exact at this step,
 * and on the progress bar it is a sub-pixel move for anything longer than about ten minutes - while
 * cutting the recompositions a playing episode causes by roughly an order of magnitude. The Now
 * Playing screen deliberately does not go through here; its clock reads
 * [com.solewis.podcaster.player.Playback.progress] directly.
 */
const val LIST_POSITION_STEP_MILLIS = 5_000L
