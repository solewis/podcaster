package com.solewis.podcaster.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlin.math.abs
import kotlin.math.max

/**
 * Moves a resume position into the copy of the episode that is actually playing, when that copy is
 * a different length from the one the position was saved against.
 *
 * A saved position is a millisecond offset into one particular copy of a file. Hosts that insert
 * ads per request hand out copies whose lengths differ, so the same offset can be minutes from the
 * same moment of the show. Confirmed from a phone: an episode measured at 138:47 one week came back
 * at 133:33 the next, its resume position of 134:05 was now past the end, and the player clamped
 * to the end, declared the episode finished and moved on to the next in the queue - the last 4.7
 * minutes skipped outright, without a sound.
 *
 * Where the show's content now sits cannot be known exactly without comparing the audio itself, so
 * this does two things. It scales the position by the change in length, which is right when the
 * ads that changed were spread through the episode; then it backs up by the whole change, which is
 * the worst-case error of the scaling. Together they guarantee nothing is skipped, at the cost of
 * possibly hearing up to that much again - chosen deliberately over a smaller rewind that could
 * still skip content.
 *
 * A copy of the same length is taken to be the same copy. That is not guaranteed - two copies of
 * one episode have been seen at an identical length with their ads arranged differently - but
 * nothing short of comparing audio could tell them apart. Where that case did its damage was
 * within a single listen, split across two requests - a different problem, solved by keeping one
 * listen on one download rather than here.
 *
 * Applied once the new copy reports its length, and only if playback is still where it was loaded:
 * a seek made in the meantime is the listener's own choice, and overriding it would be worse than
 * the problem this exists for.
 */
class PositionRemapper(
    private val player: Player,
    private val log: PlaybackLog? = null
) : Player.Listener {

    private data class Pending(val episodeId: String, val savedMs: Long, val recordedMs: Long)

    private var pending: Pending? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val recorded = mediaItem?.mediaMetadata?.extras
            ?.getLong(EXTRA_RECORDED_DURATION_MS, NOT_RECORDED)
            ?.takeIf { it > 0 }
        // Read straight after the item is set, when the player's position is exactly the resume
        // position it was loaded with.
        val saved = player.currentPosition
        pending = if (mediaItem != null && recorded != null && saved > 0) {
            Pending(mediaItem.mediaId, saved, recorded)
        } else {
            null
        }
        // ExoPlayer reports the new timeline before the item transition, so if the length was
        // already known in it, no later timeline change is coming to act on.
        tryRemap()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) = tryRemap()

    private fun tryRemap() {
        val waiting = pending ?: return
        if (player.currentMediaItem?.mediaId != waiting.episodeId) {
            pending = null
            return
        }
        // The first timeline for a fresh item carries no duration yet; the real one follows once
        // the start of the file has been read.
        val newDuration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        pending = null

        if (abs(player.currentPosition - waiting.savedMs) > USER_MOVED_TOLERANCE_MS) return
        val target = remappedStartPosition(waiting.savedMs, waiting.recordedMs, newDuration) ?: return
        log?.record(
            "REMAP",
            "item=${waiting.episodeId} recordedDur=${waiting.recordedMs} newDur=$newDuration " +
                "from=${waiting.savedMs} to=$target"
        )
        player.seekTo(target)
    }

    companion object {
        const val EXTRA_RECORDED_DURATION_MS = "podcaster.recordedDurationMs"
        private const val NOT_RECORDED = -1L
        /** Generous enough to absorb anything the listener did not do themselves. */
        private const val USER_MOVED_TOLERANCE_MS = 1_500L
    }
}

/**
 * Where a position saved against a copy [recordedDurationMs] long belongs in one [newDurationMs]
 * long, or null when the two are the same length and nothing should move.
 *
 * Scaled by the change in length, then backed up by the whole change - see [PositionRemapper] for
 * why the full change and not less. Never placed within [END_MARGIN_MS] of the end, so a remapped
 * position cannot itself be the thing that ends the episode before anything is heard.
 */
internal fun remappedStartPosition(savedMs: Long, recordedDurationMs: Long, newDurationMs: Long): Long? {
    val change = newDurationMs - recordedDurationMs
    if (abs(change) <= LENGTH_TOLERANCE_MS || recordedDurationMs <= 0) return null
    val scaled = savedMs.toDouble() * newDurationMs / recordedDurationMs
    val latest = max(0L, newDurationMs - END_MARGIN_MS)
    return (scaled - abs(change)).toLong().coerceIn(0L, latest)
}

/**
 * Below this, two copies are treated as the same length. Copies of one file report identical
 * durations, so this only has to absorb rounding, not real variation.
 */
internal const val LENGTH_TOLERANCE_MS = 2_000L

private const val END_MARGIN_MS = 10_000L
