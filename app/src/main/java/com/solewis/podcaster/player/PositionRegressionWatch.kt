package com.solewis.podcaster.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Notices playback going backwards when nothing asked it to.
 *
 * Written because the first log pulled off a phone could not see the reported fault at all. Every
 * backwards jump in it was accounted for - a skip button, or an episode change - and yet the audio
 * had audibly repeated a few seconds. A *position* log cannot catch that, because the position
 * never changed in a way Media3 reports: `onPositionDiscontinuity` fires for seeks and playlist
 * edits, not for the audio sink being reset underneath the renderer. When an `AudioTrack` is
 * flushed and restarted - a route change, a Bluetooth resync, a focus transition - what it has
 * already buffered can play again, and the position ExoPlayer derives from the sink's playback head
 * moves back with it. No seek, no discontinuity, nothing in the log, and a listener who plainly
 * heard the last few seconds twice.
 *
 * Not covered by a test, and the attempts are worth recording so nobody repeats them. Forcing a
 * real regression needs an audio route change no test can stage. A test that a *legitimate* seek is
 * not reported passed with the discontinuity reset removed - because a seek puts the player into
 * BUFFERING, `isPlaying` goes false, and the baseline is discarded before any comparison happens,
 * so it proved nothing. This is verified by running it and reading the log, which is the level the
 * thing being built justifies.
 *
 * So this watches the position itself. It samples while playing, and records any backwards movement
 * that no discontinuity accounts for, which is the one observation that distinguishes "the app
 * seeked" from "the audio pipeline replayed". Either answer is worth having: the first is a bug in
 * this codebase, the second is not, and until now there was no way to tell them apart.
 */
class PositionRegressionWatch(
    private val player: ExoPlayer,
    private val log: PlaybackLog,
    scope: CoroutineScope
) : Player.Listener {

    private var lastPosition: Long? = null
    private var lastItemId: String? = null

    init {
        scope.launch {
            while (true) {
                delay(SAMPLE_INTERVAL_MILLIS)
                sample()
            }
        }
    }

    /**
     * Any reported discontinuity resets the baseline, so a seek the user asked for is never
     * reported as a regression. That is the whole discrimination: what is left after discarding
     * every explained jump is an unexplained one.
     */
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        lastPosition = null
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        lastPosition = null
    }

    private fun sample() {
        if (!player.isPlaying) {
            // Not a baseline worth keeping: a pause can sit for hours, and the position on the
            // other side of it is not comparable.
            lastPosition = null
            return
        }
        val itemId = player.currentMediaItem?.mediaId
        val position = player.currentPosition
        val previous = lastPosition
        val previousItem = lastItemId
        lastPosition = position
        lastItemId = itemId

        if (previous == null || itemId != previousItem) return
        val moved = position - previous
        if (moved < -TOLERANCE_MILLIS) {
            log.record(
                "POSITION_WENT_BACK",
                "from=$previous to=$position by=${-moved} item=$itemId " +
                    "(no seek or transition accounts for this)"
            )
        }
    }

    private companion object {
        /**
         * Half a second, which bounds how much of a regression can be missed while still costing
         * one cheap field read per tick. The reported fault was several seconds, comfortably above
         * anything this rate would blur.
         */
        const val SAMPLE_INTERVAL_MILLIS = 500L

        /**
         * The position ExoPlayer reports is derived from the audio sink's playback head and drifts
         * by a few tens of milliseconds either way, so a small backwards step is normal and only a
         * real regression is worth a line. Well under the reported "few seconds".
         */
        const val TOLERANCE_MILLIS = 400L
    }
}
