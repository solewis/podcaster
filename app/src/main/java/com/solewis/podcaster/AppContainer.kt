package com.solewis.podcaster

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.room.Room
import com.solewis.podcaster.data.db.MIGRATION_1_2
import com.solewis.podcaster.data.db.PodcasterDatabase
import com.solewis.podcaster.data.remote.FeedFetcher
import com.solewis.podcaster.data.remote.ItunesSearchApi
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.PodcastRepository
import com.solewis.podcaster.data.repo.QueueRepository
import com.solewis.podcaster.data.repo.SearchRepository
import com.solewis.podcaster.data.repo.ShowPreviewRepository
import com.solewis.podcaster.data.repo.SubscriptionRepository
import com.solewis.podcaster.player.PlayerConnection
import java.io.File

/**
 * Manual dependency graph - no Hilt/Koin. The graph is about a dozen singletons with zero
 * alternative bindings, which is exactly the case where a DI framework is pure overhead; it also
 * keeps generated code out of stack traces around the playback service, which is hard enough to
 * debug on its own.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database: PodcasterDatabase = Room.databaseBuilder(
        appContext,
        PodcasterDatabase::class.java,
        PodcasterDatabase.NAME
    )
        .addMigrations(MIGRATION_1_2)
        .build()

    private val feedFetcher = FeedFetcher()

    val searchRepository = SearchRepository(ItunesSearchApi())
    val subscriptionRepository = SubscriptionRepository(database.podcastDao(), database.episodeDao(), feedFetcher)
    val episodeRepository = EpisodeRepository(database.episodeDao(), database.podcastDao())
    val podcastRepository = PodcastRepository(database.podcastDao())
    val showPreviewRepository = ShowPreviewRepository(feedFetcher)
    val queueRepository = QueueRepository(database.queueDao(), episodeRepository)

    /**
     * Must be a process-wide singleton: a second [SimpleCache] instance pointed at the same
     * directory throws `IllegalStateException`. Read by both [PlayerConnection]'s playback path
     * (via `PlaybackService`) and, eventually, a download feature - see [PlayerFactory][com.solewis.podcaster.player.PlayerFactory]'s
     * doc for why sharing one cache instance across both matters.
     */
    val mediaCache: SimpleCache by lazy {
        SimpleCache(
            File(appContext.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES),
            StandaloneDatabaseProvider(appContext)
        )
    }

    val playerConnection: PlayerConnection by lazy { PlayerConnection(appContext) }

    private companion object {
        const val CACHE_SIZE_BYTES = 512L * 1024 * 1024
    }
}
