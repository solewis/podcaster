package com.solewis.podcaster.testing

import androidx.lifecycle.ViewModel
import com.solewis.podcaster.data.db.PodcasterDatabase
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.PodcastRepository
import com.solewis.podcaster.data.repo.QueueRepository
import com.solewis.podcaster.data.repo.SubscriptionRepository
import com.solewis.podcaster.data.remote.FeedFetcher
import com.solewis.podcaster.AppContainer
import com.solewis.podcaster.player.PlaybackStarter
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The Room-backed half of the app's dependency graph, assembled per test.
 *
 * Real repositories over a real (in-memory) database rather than mocked ones: the queries are where
 * most of the ordering and filtering behavior actually lives, so stubbing them out would leave a
 * ViewModel test asserting little more than that the ViewModel calls the method it calls.
 */
class TestGraph : Closeable {

    /**
     * Room's two executors, owned here so that [close] can wait for them.
     *
     * Single-threaded and separate, which is the whole point: a task submitted to a single-threaded
     * executor runs only once everything already queued has, so waiting for a no-op is waiting for
     * Room to be idle. They must be two, not one - a transaction occupies its executor while
     * issuing queries on the other, and sharing one thread between them deadlocks.
     */
    private val queryExecutor: ExecutorService = Executors.newSingleThreadExecutor(::daemonThread)
    private val transactionExecutor: ExecutorService = Executors.newSingleThreadExecutor(::daemonThread)

    val db: PodcasterDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PodcasterDatabase::class.java
    )
        .setQueryExecutor(queryExecutor)
        .setTransactionExecutor(transactionExecutor)
        .build()
    val playback = FakePlayback()
    val downloads = FakeDownloads()
    val connectivity = FakeConnectivity()

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
    /**
     * Owns the lifetime of the graph's app-scoped coroutines, so they end with the test rather than
     * outliving it on a `Dispatchers.Main` that the next test is about to replace.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The one route into playback, so a test can take the network away and see what happens. */
    val playbackStarter by lazy { PlaybackStarter(playback, downloads, connectivity, appScope) }

    fun appContainer(httpClient: OkHttpClient = OkHttpClient()): AppContainer = AppContainer(
        context = ApplicationProvider.getApplicationContext(),
        database = db,
        httpClient = httpClient,
        playbackFactory = { playback },
        downloadsOverride = downloads,
        appScope = appScope,
        connectivity = connectivity
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
        appScope.cancel()
        // Then wait for Room itself, because cancelling does not do that. A write already handed to
        // Room is not interruptible - it runs on Room's own executor, and the statement underneath
        // it is a blocking call - so closing here raced it, and the write landed on a shut
        // connection:
        //
        //     android.database.SQLException: Error code: 21, message: connection is closed
        //
        // Which never failed the test that caused it. The throw happens on a Room executor thread
        // inside a coroutine nobody awaits, so it reaches the default uncaught handler and the
        // runner blames whichever test is running - a different one each time, and one that passes
        // in isolation. Seen on CI as ResumeJourneyTest and locally as NotificationTapTest.
        //
        // Transactions first: one of those can queue further work on the query executor, so
        // draining the query executor first would leave exactly the gap this closes.
        awaitIdle(transactionExecutor)
        awaitIdle(queryExecutor)
        db.close()
        queryExecutor.shutdownNow()
        transactionExecutor.shutdownNow()
    }

    /**
     * Blocks until [executor] has run everything queued on it. Shutting it down instead would
     * reject whatever a still-unwinding coroutine submits next, trading one stray exception for
     * another - so nothing is shut down until the database is already closed.
     */
    private fun awaitIdle(executor: ExecutorService) {
        executor.submit {}.get(IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        /** Long enough to be a real wait on a loaded CI runner, short enough to fail rather than hang. */
        const val IDLE_TIMEOUT_SECONDS = 20L

        /**
         * Daemon, so a graph whose [close] never runs - a test that threw in `@Before`, say - cannot
         * hold the test JVM open. There is one of these per test, so a non-daemon leak would be a
         * hang rather than a failure, and a hang says far less about what went wrong.
         */
        private fun daemonThread(runnable: Runnable): Thread =
            Thread(runnable, "test-room").apply { isDaemon = true }
    }
}
