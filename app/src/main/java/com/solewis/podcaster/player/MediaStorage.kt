package com.solewis.podcaster.player

import android.content.Context
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import java.io.File
import java.util.concurrent.Executors

/**
 * Everything media playback keeps on disk, held at *process* scope rather than per
 * [com.solewis.podcaster.AppContainer].
 *
 * This exists because of a constraint that cannot be satisfied anywhere else. `SimpleCache` allows
 * exactly one instance per directory per process and throws otherwise, and `DownloadManager` owns
 * the write lock on its cache and index - yet `AppContainer` is deliberately instantiable more than
 * once, since an instrumentation test builds its own over an in-memory database and installs it
 * alongside the one `Application.onCreate` already made. That was harmless only for as long as
 * nothing in the UI touched downloads: the moment a list row started observing them, both
 * containers opened the download cache and the second threw
 * `Another SimpleCache instance uses the folder`. Found by the on-device smoke tests, which are the
 * only place two containers coexist.
 *
 * So the ownership sits here, in the one scope that matches what Media3 actually requires. The
 * container still exposes these; it just no longer pretends to own them.
 */
@UnstableApi
object MediaStorage {

    private val lock = Any()

    @Volatile private var databaseProvider: DatabaseProvider? = null
    @Volatile private var streamCache: SimpleCache? = null
    @Volatile private var downloadCache: SimpleCache? = null
    @Volatile private var downloadManager: DownloadManager? = null

    /** Shared by both caches and by the download index - all three are Media3-managed tables. */
    fun databaseProvider(context: Context): DatabaseProvider = synchronized(lock) {
        databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext)
            .also { databaseProvider = it }
    }

    /**
     * Opportunistic cache for streamed audio, so re-listening to the last few minutes (or an app
     * restart mid-episode) doesn't refetch. Bounded and evicted least-recently-used, and in
     * `cacheDir` so the system can reclaim it under storage pressure.
     */
    fun streamCache(context: Context): SimpleCache = synchronized(lock) {
        streamCache ?: SimpleCache(
            File(context.applicationContext.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(STREAM_CACHE_SIZE_BYTES),
            databaseProvider(context)
        ).also { streamCache = it }
    }

    /**
     * Deliberately a *separate* cache from [streamCache], with a [NoOpCacheEvictor] and in
     * `filesDir` rather than `cacheDir`.
     *
     * Both differences are load-bearing. An episode the user explicitly downloaded to listen to
     * offline must not be thrown away to make room for something streamed - which is exactly what
     * would happen if downloads shared the LRU-evicted cache, silently and at the worst possible
     * moment. And `cacheDir` is reclaimable by the system whenever storage runs low, which is the
     * one thing a download must never be.
     *
     * The flip side is that nothing evicts this: its size is governed only by what the user
     * downloads and deletes.
     */
    fun downloadCache(context: Context): SimpleCache = synchronized(lock) {
        downloadCache ?: SimpleCache(
            File(context.applicationContext.filesDir, "downloads"),
            NoOpCacheEvictor(),
            databaseProvider(context)
        ).also { downloadCache = it }
    }

    /** Media3's download engine, which owns the transfers and its own index. */
    fun downloadManager(context: Context): DownloadManager = synchronized(lock) {
        downloadManager ?: DownloadManager(
            context.applicationContext,
            databaseProvider(context),
            downloadCache(context),
            DefaultHttpDataSource.Factory().setUserAgent(PlayerFactory.USER_AGENT),
            Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS)
        ).apply {
            maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
            // Any connection, not unmetered only: pressing download is a request for the episode
            // now, and silently waiting for wifi looks identical to being broken. Auto-download,
            // which nobody asked for episode-by-episode, is where NETWORK_UNMETERED belongs.
            requirements = Requirements(Requirements.NETWORK)
        }.also { downloadManager = it }
    }

    private const val STREAM_CACHE_SIZE_BYTES = 512L * 1024 * 1024
    private const val MAX_PARALLEL_DOWNLOADS = 3
}
