package com.solewis.podcaster.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.solewis.podcaster.data.repo.QueueRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
     * Any seek since the load - the listener's or [PositionRemapper]'s - means an ending is no
     * longer the load's doing. Without this, skipping straight past an outro on resume (which ends
     * the episode instantly) would be logged as a bad load.
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

        // Recorded, deliberately not acted on. An earlier version rewound five minutes here, but
        // PositionRemapper already repairs the case that prompted it (a resume past the end of a
        // shorter copy) whenever the earlier copy's length is known - which is every resume, since
        // a position is only ever saved while playing, and playing measures the length. What is
        // left is a host that never states a length, or a bug in the remapper; a guessed jump was a
        // poor answer to either, so this only leaves the evidence for whoever reads the log.
        if (endedWithoutBeingHeard()) {
            log?.record("ENDED_UNHEARD", "item=$endedEpisodeId loadedAt=$loadedAtMs dur=${player.duration}")
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
     * An episode that reached its end within moments of being loaded at a resume position, with no
     * seek since - loaded somewhere past what this copy of the file holds, rather than listened to.
     */
    private fun endedWithoutBeingHeard(): Boolean =
        !seekedSinceLoad && loadedAtMs > 0 && player.currentPosition - loadedAtMs < MIN_HEARD_MS

    private companion object {
        /** Less than this between loading and ending means nothing was heard. */
        const val MIN_HEARD_MS = 3_000L
    }
}
