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
    /** Read per event, not captured, so turning it off takes effect on the episode ending now. */
    private val enabled: () -> Boolean = { true }
) : Player.Listener {

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_ENDED) return
        // Checked here rather than at construction: with this off, an episode ending is simply the
        // end - the queue is left intact for whenever it is asked for.
        if (!enabled()) return
        val endedEpisodeId = player.currentMediaItem?.mediaId ?: return

        scope.launch {
            val next = queueRepository.nextPlayable(endedEpisodeId) ?: return@launch
            player.setMediaItem(MediaItemMapper.toMediaItem(next), next.startPositionMillis)
            player.prepare()
            player.play()
        }
    }
}
