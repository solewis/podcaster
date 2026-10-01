package com.solewis.podcaster.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.solewis.podcaster.data.repo.QueueRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Plays whatever comes next - the front of the personal queue, else the next unplayed episode in
 * the same show - once the current one finishes on its own. A manual skip (seeking, pressing
 * skip-to-next) is not "the current one finishing", so this only ever fires from
 * [Player.STATE_ENDED], never from a user-initiated item change.
 */
class AutoAdvancer(
    private val player: ExoPlayer,
    private val queueRepository: QueueRepository,
    private val scope: CoroutineScope,
    private val log: PlaybackLog? = null,
    /**
     * Consulted per ended episode rather than captured, so a decision taken while this episode was
     * still playing - switching auto-advance off, arming the sleep timer - applies to this ending.
     *
     * One gate for both callers on purpose: they answer the same question, and two independent
     * checks would leave the order between them undefined.
     */
    private val shouldAdvance: () -> Boolean = { true }
) : Player.Listener {

    /** Where the current episode was loaded - see [endedWithoutBeingHeard]. */
    private var loadedAtMs = 0L

    /**
     * Any seek since the load - the listener's, [PositionRemapper]'s or this class's own - means an
     * ending is no longer the load's doing. Without this, skipping forward past an outro (which
     * ends the episode instantly) would read as "never heard" and throw you back five minutes, and
     * this class's own rewind would make the episode's real ending look unheard too, in a loop.
     */
    private var seekedSinceLoad = false

    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
        loadedAtMs = player.currentPosition
        seekedSinceLoad = false
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) seekedSinceLoad = true
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_ENDED) return
        val endedEpisodeId = player.currentMediaItem?.mediaId ?: return

        if (endedWithoutBeingHeard()) {
            // Not finished - never started. Seeking back off the end resumes playback (it was
            // meant to be playing, and still is), and the progress writer's next write records a
            // mid-episode position, which also clears the "played" it wrote on reaching the end.
            val target = max(0L, player.duration - UNHEARD_FALLBACK_REWIND_MS)
            log?.record("ENDED_UNHEARD", "item=$endedEpisodeId loadedAt=$loadedAtMs dur=${player.duration} rewindTo=$target")
            // Set here as well as by the discontinuity it causes, so a second ending can never
            // land back in this branch even if the callbacks arrive out of order.
            seekedSinceLoad = true
            player.seekTo(target)
            return
        }

        // Declining here *is* stopping: an episode reaching its end already leaves the player
        // stopped, so there is nothing further to pause. The queue is left intact either way.
        if (!shouldAdvance()) return

        scope.launch {
            val next = queueRepository.nextPlayable(endedEpisodeId) ?: return@launch
            // `next` coming back as the episode that just ended would replay it, which is one
            // shape the reported jump-back could take - so both ids go in the log, not just one.
            log?.record(
                "AUTO_ADVANCE",
                "ended=$endedEpisodeId next=${next.episodeId} pos=${next.startPositionMillis}"
            )
            player.setMediaItem(MediaItemMapper.toMediaItem(next), next.startPositionMillis)
            player.prepare()
            player.play()
        }
    }

    /**
     * An episode that reached its end within moments of being loaded at a resume position was never
     * listened to; it was loaded somewhere past what this copy of the file holds.
     *
     * The case that prompted this: a queued episode's saved position had landed past the end of a
     * shorter, re-stitched copy, so it "ended" twenty milliseconds after becoming ready and this
     * class moved straight on to the next in the queue, silently skipping what was left.
     * [PositionRemapper] repairs that position before it can happen whenever the earlier copy's
     * length is known; this is the backstop for when it is not, and so has no length to work from -
     * hence a fixed rewind rather than a computed one.
     */
    private fun endedWithoutBeingHeard(): Boolean =
        !seekedSinceLoad && loadedAtMs > 0 && player.currentPosition - loadedAtMs < MIN_HEARD_MS

    private companion object {
        /** Less than this between loading and ending means nothing was heard. */
        const val MIN_HEARD_MS = 3_000L
        /**
         * A fixed guess, since this path has no length to compute from: comfortably more than the
         * largest change in an episode's length seen so far (5¼ minutes), so it should land before
         * anything still to come - at the cost of repeating some of what came before.
         */
        const val UNHEARD_FALLBACK_REWIND_MS = 5L * 60 * 1000
    }
}
