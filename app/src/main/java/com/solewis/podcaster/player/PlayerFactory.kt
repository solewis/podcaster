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
 * data-source artifact needed, verified before building this. Both [SimpleCache]s are passed in
 * rather than constructed here because each must be a process-wide singleton (a second instance on
 * the same directory throws) - see [com.solewis.podcaster.AppContainer].
 */
object PlayerFactory {

    /** Also used for downloads, so both halves of the app identify themselves the same way. */
    const val USER_AGENT = "Podcaster/1.0 (Android; personal use, not for distribution)"


    /**
     * [downloadCache] is read first and never written, [streamCache] beneath it absorbs everything
     * streamed. Nesting them this way is what makes a downloaded episode play from disk without
     * the playback path needing to know it was downloaded: the media id and `customCacheKey` are
     * the same either way (see [MediaItemMapper]), so the download either hits or it doesn't.
     *
     * The read-only part matters. If playback could write into the download cache, a streamed
     * episode would leave partial data there - indistinguishable from a real download to anything
     * measuring how much space downloads occupy, and never evicted, since that cache has no
     * evictor by design.
     */
    // No seek increments set here on purpose: they are fixed at build time and so cannot carry a
    // user setting. TimedSkipPlayer, which wraps this player for the session, owns them instead -
    // setting them in both places would just be a second source of truth for the same number.
    fun create(context: Context, downloadCache: SimpleCache, streamCache: SimpleCache): ExoPlayer {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT)
        val streamCacheFactory = CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(streamCacheFactory)
            .setCacheWriteDataSinkFactory(null)
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
            .build()
    }
}
