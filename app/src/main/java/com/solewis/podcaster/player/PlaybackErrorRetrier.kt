package com.solewis.podcaster.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.solewis.podcaster.data.net.Connectivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Makes a dropped connection something playback recovers from, rather than something that leaves
 * it silently dead until the app is force-killed and restarted.
 *
 * Confirmed the hard way: reproduced on a fresh emulator by cutting all network mid-episode.
 * ExoPlayer reports [PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED] and moves to
 * `STATE_IDLE` - and then, with nothing in this codebase calling `prepare()` again, sits there
 * forever. Not for a while: waited 24+ seconds, skipped forward and back, and even turned the
 * network back on - nothing happened, because nothing was watching for it to come back. The app
 * kept reporting `playWhenReady = true` throughout, so the UI looked exactly like it was still
 * playing.
 *
 * A raw [ExoPlayer] listener rather than living on [PlayerConnection], because `player.prepare()`
 * has to run on the player it belongs to, and that player lives in this service's process - a
 * `MediaController` on the UI side has no such method, by design.
 *
 * [PlayerConnection] independently derives its own "still waiting" state from watching the same
 * `onPlaybackStateChanged`/`onPlayerError` events over its `MediaController` - it does not
 * coordinate with this class directly. That is deliberate: this class's only job is to make
 * `prepare()` happen again; whether anything on screen says so is somebody else's concern.
 */
class PlaybackErrorRetrier(
    private val player: ExoPlayer,
    private val connectivity: Connectivity,
    private val scope: CoroutineScope,
    private val log: PlaybackLog? = null,
    private val policy: ReconnectPolicy = ReconnectPolicy()
) : Player.Listener {

    private var retryJob: Job? = null

    override fun onPlayerError(error: PlaybackException) {
        if (!error.isRecoverableNetworkError()) return
        // A second error while already retrying is the *same* condition continuing, not a new
        // one - restarting the clock would let a connection that never truly recovers retry
        // forever in practice, one error at a time.
        if (retryJob?.isActive == true) return
        retryJob = scope.launch { retryUntilRecoveredOrGivenUp() }
    }

    /**
     * Anything that means the player moved on without our help - a new item, an explicit stop -
     * makes a pending retry not just pointless but actively wrong: calling `prepare()` on a player
     * someone else has since redirected would undo whatever they asked for.
     */
    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
        cancelRetry()
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    private suspend fun retryUntilRecoveredOrGivenUp() {
        val startedAt = System.currentTimeMillis()
        var attempt = 0
        while (true) {
            val elapsed = System.currentTimeMillis() - startedAt
            if (!policy.shouldKeepTrying(elapsed)) {
                log?.record("RECONNECT_GIVE_UP", "afterMs=$elapsed attempts=$attempt")
                return
            }

            attempt++
            delay(policy.delayBeforeAttempt(attempt))

            // Checked once, after waking up - not before, which would only ever repeat whatever
            // the end of the previous iteration already established.
            if (!player.playWhenReady) return

            when {
                // Genuinely recovered: actually ready to play, not merely "not idle any more".
                player.playbackState == Player.STATE_READY -> {
                    log?.record("RECONNECT_RECOVERED", "afterMs=${System.currentTimeMillis() - startedAt} attempts=$attempt")
                    return
                }
                // A prior prepare() is still resolving. STATE_BUFFERING with no error yet is not
                // recovery - it is the moment right after prepare() clears the old error and before
                // the new attempt has had a chance to fail again. Reproduced on device: without
                // this branch, a poll landing in that window logged a false RECONNECT_RECOVERED,
                // which then let a fresh retry job start from attempt 1 on the next real failure -
                // silently resetting the give-up clock forever, the opposite of what
                // giveUpAfterMillis exists for. Nothing to do but wait for the next tick.
                player.playbackState != Player.STATE_IDLE || player.playerError == null -> Unit
                !connectivity.isOnline() -> log?.record("RECONNECT_STILL_OFFLINE", "attempt=$attempt")
                else -> {
                    log?.record("RECONNECT_ATTEMPT", "attempt=$attempt pos=${player.currentPosition}")
                    player.prepare()
                }
            }
        }
    }
}
