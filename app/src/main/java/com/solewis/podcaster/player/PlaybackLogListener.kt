package com.solewis.podcaster.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Writes what the *player* did into [PlaybackLog], as distinct from what the app asked it to do.
 *
 * The distinction is the whole point. The app's own calls are recorded at their call sites, so a
 * jump that appears here with no app call before it came from inside the player - a stream that
 * ended early, a transition nobody requested - and a jump that follows an app call is ours. Either
 * answer is progress; without both halves the log would only ever confirm what we already believe.
 *
 * Attached to the real [ExoPlayer] rather than to the session wrapper, because the wrapper reports
 * seek increments the underlying player never sees and would name the wrong reason.
 */
class PlaybackLogListener(
    private val player: ExoPlayer,
    private val log: PlaybackLog
) : Player.Listener {

    /**
     * The reason codes matter more than anything else in the log. `SEEK` means something called
     * `seekTo`; `AUTO_TRANSITION` means the player moved on by itself; `REMOVE` means the playlist
     * was replaced under it. For the reported jump-back these three are entirely different bugs.
     */
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        val oldId = oldPosition.mediaItem?.mediaId
        val newId = newPosition.mediaItem?.mediaId
        // A delta only when both positions are in the same episode. Across an item change it is a
        // subtraction of two unrelated clocks, and printing it anyway was actively misleading: a
        // fresh episode starting at 0 while the outgoing one sat at 101 minutes read as
        // "delta=-6085617", which looks exactly like the bug being hunted and is not.
        val movement = if (oldId == newId) {
            "delta=${newPosition.positionMs - oldPosition.positionMs}"
        } else {
            "itemChanged"
        }
        log.record(
            "DISCONTINUITY",
            "reason=${discontinuityReason(reason)} " +
                "from=${oldPosition.positionMs} to=${newPosition.positionMs} $movement " +
                "oldItem=$oldId newItem=$newId"
        )
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // Duration alongside position, because a STATE_ENDED a minute into an hour-long episode is
        // the player believing the stream finished - a completely different fault from a seek, and
        // one only these two numbers together can distinguish.
        log.record(
            "STATE",
            "${stateName(playbackState)} pos=${player.currentPosition} " +
                "dur=${player.duration.takeIf { it != C.TIME_UNSET } ?: -1} " +
                "buffered=${player.bufferedPosition} item=${player.currentMediaItem?.mediaId}"
        )
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        log.record(
            "ITEM",
            "reason=${transitionReason(reason)} item=${mediaItem?.mediaId} pos=${player.currentPosition}"
        )
    }

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        log.record(
            "TIMELINE",
            "reason=${if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) "PLAYLIST" else "SOURCE_UPDATE"} " +
                "windows=${timeline.windowCount} dur=${player.duration.takeIf { it != C.TIME_UNSET } ?: -1}"
        )
    }

    /**
     * With its reason, which names the cause of a pause the user did not ask for - audio focus lost
     * to another app, or the headphones coming out.
     */
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        log.record("PLAY_WHEN_READY", "$playWhenReady reason=${playWhenReadyReason(reason)}")
    }

    override fun onPlayerError(error: PlaybackException) {
        log.record("ERROR", "${error.errorCodeName} pos=${player.currentPosition} ${error.message}")
    }

    private fun discontinuityReason(reason: Int) = when (reason) {
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
        Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
        Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
        Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
        Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
        Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
        else -> "UNKNOWN($reason)"
    }

    private fun transitionReason(reason: Int) = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
        else -> "UNKNOWN($reason)"
    }

    private fun playWhenReadyReason(reason: Int) = when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "BECOMING_NOISY"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_ITEM"
        Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "SUPPRESSED_TOO_LONG"
        else -> "UNKNOWN($reason)"
    }

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }
}
