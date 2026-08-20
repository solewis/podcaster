package com.solewis.podcaster.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Builds the single [ExoPlayer] instance the playback service uses.
 *
 * `media3-exoplayer` alone is sufficient for MP3/M4A over HTTPS - no extra HLS or OkHttp
 * data-source artifact needed, verified before building this. [SimpleCache] is passed in rather
 * than constructed here because it must be a process-wide singleton (a second instance on the
 * same cache directory throws) - see [com.solewis.podcaster.AppContainer].
 */
object PlayerFactory {

    private const val USER_AGENT = "Podcaster/1.0 (Android; personal use, not for distribution)"
    private const val SEEK_BACK_MS = 15_000L
    private const val SEEK_FORWARD_MS = 30_000L

    fun create(context: Context, cache: SimpleCache): ExoPlayer {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
    }
}
