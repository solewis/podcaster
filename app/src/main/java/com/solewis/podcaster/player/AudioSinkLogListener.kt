package com.solewis.podcaster.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink

/**
 * The audio pipeline's own events, which is where the reported repeat has to be if it is not a seek.
 *
 * [PlaybackLogListener] records what the *player* did; this records what the *audio sink* did, and
 * the difference is the whole reason this file exists. When an `AudioTrack` is flushed and
 * restarted, the audio it has already accepted but not yet played can play again - and none of the
 * `Player.Listener` callbacks say a word about it, because as far as the player is concerned nothing
 * discontinuous happened. A listener hears the last few seconds twice; the position log is silent.
 *
 * The buffer size in [onAudioTrackInit] is the number that bounds how much can repeat, so it is
 * worth having even when nothing goes wrong: it says whether a few seconds of replay is even
 * physically possible on this device, or whether the explanation has to be somewhere else.
 *
 * `AnalyticsListener` rather than `Player.Listener` because these callbacks exist nowhere else.
 */
@UnstableApi
class AudioSinkLogListener(
    private val player: ExoPlayer,
    private val log: PlaybackLog
) : AnalyticsListener {

    /**
     * The sink ran dry. On its own this is a glitch rather than a repeat, but it is the same
     * underlying condition - the renderer losing its grip on the sink - and it arrives with the
     * numbers that say how badly.
     */
    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long
    ) {
        log.record(
            "AUDIO_UNDERRUN",
            "bufferMs=$bufferSizeMs sinceLastFeedMs=$elapsedSinceLastFeedMs pos=${player.currentPosition}"
        )
    }

    /**
     * A new `AudioTrack`, which means the old one was torn down - and anything it still held goes
     * with it, or comes back. Logged with its buffer size, which is the ceiling on how much audio
     * a single reset can repeat.
     */
    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig
    ) {
        log.record(
            "AUDIO_TRACK_INIT",
            "encoding=${audioTrackConfig.encoding} rate=${audioTrackConfig.sampleRate} " +
                "bufferBytes=${audioTrackConfig.bufferSize} offload=${audioTrackConfig.offload} " +
                "pos=${player.currentPosition}"
        )
    }

    override fun onAudioTrackReleased(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig
    ) {
        log.record("AUDIO_TRACK_RELEASED", "pos=${player.currentPosition}")
    }

    /**
     * Fires when the sink starts advancing again, which after a reset is the moment the repeated
     * audio begins. Paired with the position, two of these close together with the position lower
     * the second time is the fault, stated as plainly as the pipeline can state it.
     */
    override fun onAudioPositionAdvancing(
        eventTime: AnalyticsListener.EventTime,
        playoutStartSystemTimeMs: Long
    ) {
        log.record("AUDIO_ADVANCING", "pos=${player.currentPosition}")
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        log.record("AUDIO_SINK_ERROR", "${audioSinkError::class.java.simpleName} ${audioSinkError.message}")
    }

    override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
        log.record("AUDIO_CODEC_ERROR", "${audioCodecError::class.java.simpleName} ${audioCodecError.message}")
    }
}
