package com.solewis.podcaster

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
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
import com.solewis.podcaster.player.Playback
import com.solewis.podcaster.player.PlayerConnection
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
    playbackFactory: (Context) -> Playback = ::PlayerConnection
) {

    private val appContext = context.applicationContext

    private val feedFetcher = FeedFetcher(httpClient)

    val searchRepository = SearchRepository(ItunesSearchApi(httpClient))
    val subscriptionRepository = SubscriptionRepository(database.podcastDao(), database.episodeDao(), feedFetcher)
    val episodeRepository = EpisodeRepository(database.episodeDao(), database.podcastDao())
    val podcastRepository = PodcastRepository(database.podcastDao())
    val showPreviewRepository = ShowPreviewRepository(feedFetcher)
    val queueRepository = QueueRepository(database.queueDao(), episodeRepository)

    /**
     * Must be a process-wide singleton: a second [SimpleCache] instance pointed at the same
     * directory throws `IllegalStateException`. Read by both the playback path (via
     * `PlaybackService`) and, eventually, a download feature - see [PlayerFactory][com.solewis.podcaster.player.PlayerFactory]'s
     * doc for why sharing one cache instance across both matters.
     */
    val mediaCache: SimpleCache by lazy {
        SimpleCache(
            File(appContext.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES),
            StandaloneDatabaseProvider(appContext)
        )
    }

    /** Lazy so a screen test that never plays anything never binds to the playback service. */
    val playback: Playback by lazy { playbackFactory(appContext) }

    companion object {
        private const val CACHE_SIZE_BYTES = 512L * 1024 * 1024

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
