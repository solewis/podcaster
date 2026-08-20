package com.solewis.podcaster

import android.content.Context
import androidx.room.Room
import com.solewis.podcaster.data.db.PodcasterDatabase
import com.solewis.podcaster.data.remote.FeedFetcher
import com.solewis.podcaster.data.remote.ItunesSearchApi
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.PodcastRepository
import com.solewis.podcaster.data.repo.SearchRepository
import com.solewis.podcaster.data.repo.SubscriptionRepository

/**
 * Manual dependency graph - no Hilt/Koin. The graph is about a dozen singletons with zero
 * alternative bindings, which is exactly the case where a DI framework is pure overhead; it also
 * keeps generated code out of stack traces around the playback service, which is hard enough to
 * debug on its own once that exists (Phase 4+).
 */
class AppContainer(context: Context) {

    private val database: PodcasterDatabase = Room.databaseBuilder(
        context.applicationContext,
        PodcasterDatabase::class.java,
        PodcasterDatabase.NAME
    ).build()

    private val feedFetcher = FeedFetcher()

    val searchRepository = SearchRepository(ItunesSearchApi())
    val subscriptionRepository = SubscriptionRepository(database.podcastDao(), database.episodeDao(), feedFetcher)
    val episodeRepository = EpisodeRepository(database.episodeDao())
    val podcastRepository = PodcastRepository(database.podcastDao())
}
