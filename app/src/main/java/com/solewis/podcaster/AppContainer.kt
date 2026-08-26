package com.solewis.podcaster

import android.content.Context
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import com.solewis.podcaster.data.repo.DownloadRepository
import com.solewis.podcaster.data.repo.Downloads
import com.solewis.podcaster.player.PlayerFactory
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
import com.solewis.podcaster.data.settings.SettingsStore
import com.solewis.podcaster.player.MediaStorage
import com.solewis.podcaster.player.Playback
import com.solewis.podcaster.player.PlayerConnection
import com.solewis.podcaster.player.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

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

    /** Not per-show state, so it has nowhere to live in [database] - see [SettingsStore]. */
    val settings = SettingsStore(appContext)

    /**
     * All four live in [MediaStorage] rather than here: Media3 requires one cache instance per
     * directory per process and one download manager over them, and this class is deliberately
     * built more than once (an instrumentation test installs its own alongside the real one). See
     * [MediaStorage] for what went wrong when the container owned them.
     */
    val streamCache: SimpleCache get() = MediaStorage.streamCache(appContext)
    val downloadCache: SimpleCache get() = MediaStorage.downloadCache(appContext)
    private val downloadManager: DownloadManager get() = MediaStorage.downloadManager(appContext)

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
