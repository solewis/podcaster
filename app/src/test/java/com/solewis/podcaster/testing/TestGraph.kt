package com.solewis.podcaster.testing

import com.solewis.podcaster.data.db.PodcasterDatabase
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.PodcastRepository
import com.solewis.podcaster.data.repo.QueueRepository
import com.solewis.podcaster.data.repo.SubscriptionRepository
import com.solewis.podcaster.data.remote.FeedFetcher
import java.io.Closeable

/**
 * The Room-backed half of the app's dependency graph, assembled per test.
 *
 * Real repositories over a real (in-memory) database rather than mocked ones: the queries are where
 * most of the ordering and filtering behavior actually lives, so stubbing them out would leave a
 * ViewModel test asserting little more than that the ViewModel calls the method it calls.
 */
class TestGraph : Closeable {

    val db: PodcasterDatabase = inMemoryDatabase()
    val playback = FakePlayback()

    /** Advance to make a later write observably later - `now` is read at each call, not captured. */
    var clock: Long = 1_000L

    val episodeRepository = EpisodeRepository(db.episodeDao(), db.podcastDao()) { clock }
    val podcastRepository = PodcastRepository(db.podcastDao())
    val queueRepository = QueueRepository(db.queueDao(), episodeRepository) { clock }
    val subscriptionRepository =
        SubscriptionRepository(db.podcastDao(), db.episodeDao(), FeedFetcher()) { clock }

    suspend fun insertShow(title: String = "Test Show", artworkUrl: String? = "https://example.com/show.png"): Long =
        db.podcastDao().insert(podcastRow(title = title, artworkUrl = artworkUrl))

    suspend fun insertEpisodes(vararg episodes: com.solewis.podcaster.data.db.entity.EpisodeEntity) {
        db.episodeDao().insertNew(episodes.toList())
    }

    override fun close() = db.close()
}
