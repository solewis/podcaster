package com.solewis.podcaster

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.scheduler.Requirements
import com.solewis.podcaster.data.repo.DownloadRepository
import com.solewis.podcaster.data.repo.Downloads
import com.solewis.podcaster.player.PlayerFactory
import java.util.concurrent.Executors
import androidx.room.Room
import com.solewis.podcaster.data.db.MIGRATION_1_2
import com.solewis.podcaster.data.db.PodcasterDatabase
import com.solewis.podcaster.data.remote.FeedFetcher
import com.solewis.podcaster.data.remote.HttpClient
import com.solewis.podcaster.data.remote.ItunesSearchApi
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.PodcastRepository
import com.solewis.podcaster.data.repo.QueueRepository
import com.solewis.podcaster.data.repo.SearchRepository
import com.solewis.podcaster.data.repo.ShowPreviewRepository
import com.solewis.podcaster.data.repo.SubscriptionRepository
import com.solewis.podcaster.data.settings.PlaybackSettings
import com.solewis.podcaster.player.Playback
import com.solewis.podcaster.player.PlayerConnection
import com.solewis.podcaster.player.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File

/**
 * Manual dependency graph - no Hilt/Koin. The graph is about a dozen singletons with zero
 * alternative bindings, which is exactly the case where a DI framework is pure overhead; it also
 * keeps generated code out of stack traces around the playback service, which is hard enough to
 * debug on its own.
 *
 * Every collaborator that reaches outside the process is a defaulted constructor parameter, so a
 * test can assemble the whole app against an in-memory database and a local HTTP server. That is
 * not speculative generality: without it an end-to-end test necessarily runs on the one shared
 * on-disk database the real app uses, and tests that share mutable state are the ones that fail
 * for reasons unrelated to the change under test.
 */
class AppContainer(
    context: Context,
    database: PodcasterDatabase = defaultDatabase(context),
    httpClient: OkHttpClient = HttpClient.instance,
    playbackFactory: (Context) -> Playback = ::PlayerConnection,
    /**
     * Substituted wholesale rather than built from parts, because the real one needs the two
     * `SimpleCache`s and Media3's `DownloadManager` - none of which exist on the JVM, and all of
     * which touch real directories. Null keeps the real graph.
     */
    private val downloadsOverride: Downloads? = null
) {

    private val appContext = context.applicationContext

    private val feedFetcher = FeedFetcher(httpClient)

    val searchRepository = SearchRepository(ItunesSearchApi(httpClient))
    val subscriptionRepository = SubscriptionRepository(database.podcastDao(), database.episodeDao(), feedFetcher)
    val episodeRepository = EpisodeRepository(database.episodeDao(), database.podcastDao())
    val podcastRepository = PodcastRepository(database.podcastDao())
    val showPreviewRepository = ShowPreviewRepository(feedFetcher)
    val queueRepository = QueueRepository(database.queueDao(), episodeRepository)

    /** Not per-show state, so it has nowhere to live in [database] - see [PlaybackSettings]. */
    val playbackSettings = PlaybackSettings(appContext)

    /** Shared by both caches and by [downloadManager] - Media3 keeps its own index in here. */
    private val databaseProvider by lazy { StandaloneDatabaseProvider(appContext) }

    /**
     * Opportunistic cache for streamed audio, so re-listening to the last few minutes (or an app
     * restart mid-episode) doesn't refetch. Bounded and evicted least-recently-used, and living in
     * `cacheDir` so the system can reclaim it under storage pressure.
     *
     * Must be a process-wide singleton: a second [SimpleCache] on the same directory throws.
     */
    val streamCache: SimpleCache by lazy {
        SimpleCache(
            File(appContext.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(STREAM_CACHE_SIZE_BYTES),
            databaseProvider
        )
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
     * downloads and deletes. A storage cap and auto-delete-once-played are the follow-ups.
     */
    val downloadCache: SimpleCache by lazy {
        SimpleCache(File(appContext.filesDir, "downloads"), NoOpCacheEvictor(), databaseProvider)
    }

    /**
     * Media3's own download engine, which owns its index and the actual transfers. Must be a
     * process-wide singleton for the same reason the caches are: it holds the write lock on them.
     */
    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            appContext,
            databaseProvider,
            downloadCache,
            DefaultHttpDataSource.Factory().setUserAgent(PlayerFactory.USER_AGENT),
            Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS)
        ).apply {
            maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
            // Any connection, not unmetered only: pressing download is a request for the episode
            // now, and silently waiting for wifi looks identical to being broken. Auto-download,
            // which nobody asked for episode-by-episode, is where NETWORK_UNMETERED belongs.
            requirements = Requirements(Requirements.NETWORK)
        }
    }

    /** Lazy for the same reason [playback] is: a screen test that never downloads opens no caches. */
    val downloads: Downloads by lazy {
        downloadsOverride ?: DownloadRepository(appContext, downloadManager, downloadCache, episodeRepository)
    }

    /** Lazy so a screen test that never plays anything never binds to the playback service. */
    val playback: Playback by lazy { playbackFactory(appContext) }

    /**
     * App-scoped, so it keeps counting once Now Playing is gone and the phone is face-down - which
     * is the entire point of a sleep timer. Its scope is deliberately not a ViewModel's.
     */
    val sleepTimer: SleepTimer by lazy {
        SleepTimer(playback, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    companion object {
        private const val STREAM_CACHE_SIZE_BYTES = 512L * 1024 * 1024
        private const val MAX_PARALLEL_DOWNLOADS = 3

        fun defaultDatabase(context: Context): PodcasterDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PodcasterDatabase::class.java,
                PodcasterDatabase.NAME
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
