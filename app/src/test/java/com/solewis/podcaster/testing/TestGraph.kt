package com.solewis.podcaster.testing

import androidx.lifecycle.ViewModel
import com.solewis.podcaster.data.db.PodcasterDatabase
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.PodcastRepository
import com.solewis.podcaster.data.repo.QueueRepository
import com.solewis.podcaster.data.repo.SubscriptionRepository
import com.solewis.podcaster.data.remote.FeedFetcher
import com.solewis.podcaster.AppContainer
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
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
    val downloads = FakeDownloads()

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

    /**
     * The whole app graph over this test's database, for tests that drive real screens through
     * `PodcasterRoot`. Playback and downloads are the fakes, so nothing binds to the playback
     * service and no cache directories are opened.
     */
    fun appContainer(httpClient: OkHttpClient = OkHttpClient()): AppContainer = AppContainer(
        context = ApplicationProvider.getApplicationContext(),
        database = db,
        httpClient = httpClient,
        playbackFactory = { playback },
        downloadsOverride = downloads
    )

    private val viewModels = ViewModelHost()

    /**
     * Registers a ViewModel so its `viewModelScope` is cancelled when this graph closes. See
     * [ViewModelHost] for why leaving it uncancelled makes unrelated tests fail intermittently.
     */
    fun <T : ViewModel> hosting(viewModel: T): T = viewModels.hosting(viewModel)

    override fun close() {
        // Order matters: cancel the ViewModels first so their sharing coroutines stop observing
        // before the database they are reading from disappears underneath them.
        viewModels.close()
        db.close()
    }
}
