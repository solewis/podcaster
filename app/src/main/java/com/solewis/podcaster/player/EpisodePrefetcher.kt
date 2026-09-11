package com.solewis.podcaster.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.solewis.podcaster.data.net.Connectivity
import com.solewis.podcaster.data.repo.PlayableEpisode
import com.solewis.podcaster.data.settings.PrefetchMode
import com.solewis.podcaster.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Pulls an episode onto the device as soon as it starts, so a connection that drops later has
 * nothing left to fetch.
 *
 * Exists because of a reported bug: a dropped connection deep into an episode forced
 * [PlaybackErrorRetrier] to make a fresh request to resume, and that request's own, independent
 * ad-insertion decision got written into the stream cache and played back on every later listen -
 * see [PrefetchMode]'s doc comment. A connection cannot force a reconnect over ground it has
 * already fully downloaded, which is what this is for.
 */
interface EpisodePrefetcher {

    /**
     * Starts pulling [episode] in, replacing whatever this was doing before - only one episode is
     * ever "the one currently playing", so only one is worth spending bandwidth on.
     */
    fun prefetch(episode: PlayableEpisode)
}

/** Does nothing - a downloaded episode is already fully on disk in a different store entirely. */
object NoOpEpisodePrefetcher : EpisodePrefetcher {
    override fun prefetch(episode: PlayableEpisode) = Unit
}

@UnstableApi
class CacheEpisodePrefetcher(
    private val streamCache: Cache,
    private val upstreamDataSourceFactory: DataSource.Factory,
    private val settings: SettingsStore,
    private val connectivity: Connectivity,
    private val scope: CoroutineScope
) : EpisodePrefetcher {

    /**
     * The one prefetch in flight, if any. Cancelling it is what actually stops the copy loop
     * inside [CacheWriter.cache] - it is a plain blocking call, not a suspending one, so cancelling
     * the coroutine it runs in would not by itself interrupt it.
     */
    private var current: CacheWriter? = null

    override fun prefetch(episode: PlayableEpisode) {
        current?.cancel()
        current = null

        val snapshot = settings.snapshot()
        if (snapshot.prefetchMode != PrefetchMode.FULL_EPISODE) return
        if (snapshot.prefetchWifiOnly && !connectivity.isOnWifi()) return

        // Same cache, same key, same flags as ordinary playback (see PlayerFactory) - this is
        // filling in the same store playback itself reads from, so whatever it finds already
        // cached (from playback, or a previous prefetch) is skipped rather than refetched.
        val dataSource = CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
        val spec = DataSpec.Builder()
            .setUri(Uri.parse(episode.mediaUrl))
            .setKey(episode.episodeId)
            .build()

        val writer = CacheWriter(dataSource, spec, /* temporaryBuffer = */ null, /* progressListener = */ null)
        current = writer
        scope.launch(Dispatchers.IO) {
            // Nothing downstream is waiting on this, including a failure: a prefetch that cannot
            // complete just leaves ordinary streaming to fetch what it needs when it needs it,
            // exactly as it does today.
            runCatching { writer.cache() }
        }
    }
}
