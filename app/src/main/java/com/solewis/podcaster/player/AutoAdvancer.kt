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
    /**
     * Consulted per ended episode rather than captured, so a decision taken while this episode was
     * still playing - switching auto-advance off, arming the sleep timer - applies to this ending.
     *
     * One gate for both callers on purpose: they answer the same question, and two independent
     * checks would leave the order between them undefined.
     */
    private val shouldAdvance: () -> Boolean = { true }
) : Player.Listener {

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_ENDED) return
        // Declining here *is* stopping: an episode reaching its end already leaves the player
        // stopped, so there is nothing further to pause. The queue is left intact either way.
        if (!shouldAdvance()) return
        val endedEpisodeId = player.currentMediaItem?.mediaId ?: return

        scope.launch {
            val next = queueRepository.nextPlayable(endedEpisodeId) ?: return@launch
            player.setMediaItem(MediaItemMapper.toMediaItem(next), next.startPositionMillis)
            player.prepare()
            player.play()
        }
    }
}
