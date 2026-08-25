package com.solewis.podcaster.ui.showpreview

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.repo.ShowPreviewRepository
import com.solewis.podcaster.data.remote.FeedFetcher
import com.solewis.podcaster.testing.FeedHost
import com.solewis.podcaster.testing.MainDispatcherRule
import com.solewis.podcaster.testing.TestGraph
import com.solewis.podcaster.testing.awaitTrue
import com.solewis.podcaster.testing.awaitValue
import com.solewis.podcaster.testing.podcastRow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A show reached from Search but not subscribed to, so it has no Room rows at all - everything
 * comes from the live feed fetch, and playback has to work from the raw feed data.
 */
@RunWith(AndroidJUnit4::class)
class ShowPreviewViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private lateinit var graph: TestGraph
    private lateinit var host: FeedHost

    @Before
    fun setUp() {
        graph = TestGraph()
        host = FeedHost()
    }

    @After
    fun tearDown() {
        host.close()
        graph.close()
    }

    private fun viewModel(feedUrl: String, seedTitle: String? = null) = ShowPreviewViewModel(
        feedUrl = feedUrl,
        itunesCollectionId = 7,
        seedTitle = seedTitle,
        seedArtworkUrl = null,
        showPreviewRepository = ShowPreviewRepository(FeedFetcher()),
        subscriptionRepository = graph.subscriptionRepository,
        podcastRepository = graph.podcastRepository,
        playback = graph.playback
    )

    @Test
    fun the_feed_is_fetched_and_shown() = runTest(mainDispatcher.dispatcher) {
        host.enqueueFeed("serial_with_episode_numbers.xml")

        val vm = viewModel(host.feedUrl())

        val state = vm.state.awaitValue { it.preview != null }
        assertThat(state.preview!!.title).isEqualTo("A Serial Audio Drama")
        assertThat(state.isLoading).isFalse()
        assertThat(state.error).isNull()
    }

    @Test
    fun an_already_subscribed_show_redirects_straight_to_its_real_screen() =
        runTest(mainDispatcher.dispatcher) {
            val feedUrl = "https://feeds.example/acquired"
            val id = graph.db.podcastDao().insert(podcastRow(title = "Acquired", feedUrl = feedUrl))

            val vm = viewModel(feedUrl)

            // iTunes can return one feed under several catalog entries, so Search may send you
            // here for a show you already follow. Fetching and rendering a "not subscribed yet"
            // preview would show a Subscribe button that appears to do nothing when tapped.
            val state = vm.state.awaitValue { it.subscribedPodcastId != null }
            assertThat(state.subscribedPodcastId).isEqualTo(id)
            assertThat(state.preview).isNull()
            assertThat(host.requestCount).isEqualTo(0)
        }

    @Test
    fun a_feed_that_cannot_be_loaded_reports_an_error() = runTest(mainDispatcher.dispatcher) {
        host.enqueueStatus(404)

        val vm = viewModel(host.feedUrl())

        val state = vm.state.awaitValue { it.error != null }
        assertThat(state.error).isEqualTo("Couldn't load this show")
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun an_episode_can_be_played_before_subscribing() = runTest(mainDispatcher.dispatcher) {
        host.enqueueFeed("serial_with_episode_numbers.xml")
        val vm = viewModel(host.feedUrl())
        val preview = vm.state.awaitValue { it.preview != null }.preview!!

        vm.play(preview.episodes.first())

        awaitTrue("episode handed to playback") { graph.playback.played.isNotEmpty() }
        with(graph.playback.played.single()) {
            assertThat(title).isEqualTo("Chapter 1")
            assertThat(podcastTitle).isEqualTo("A Serial Audio Drama")
            // No podcast row exists yet, so identity is the raw feed stableKey rather than the
            // "$podcastId:..." key a subscribed episode would carry.
            assertThat(episodeId).isEqualTo("serial-ep-1")
            assertThat(startPositionMillis).isEqualTo(0)
        }
    }

    @Test
    fun subscribing_hands_back_the_new_shows_id() = runTest(mainDispatcher.dispatcher) {
        host.enqueueFeed("serial_with_episode_numbers.xml")
        val vm = viewModel(host.feedUrl())
        vm.state.awaitValue { it.preview != null }
        host.enqueueFeed("serial_with_episode_numbers.xml")

        vm.subscribe()

        val state = vm.state.awaitValue { it.subscribedPodcastId != null }
        assertThat(state.isSubscribing).isFalse()
        assertThat(graph.db.podcastDao().getById(state.subscribedPodcastId!!)?.title)
            .isEqualTo("A Serial Audio Drama")
    }

    @Test
    fun a_failed_subscribe_reports_why_and_keeps_the_preview() = runTest(mainDispatcher.dispatcher) {
        host.enqueueFeed("serial_with_episode_numbers.xml")
        val vm = viewModel(host.feedUrl())
        vm.state.awaitValue { it.preview != null }
        host.enqueueStatus(500)

        vm.subscribe()

        val state = vm.state.awaitValue { it.error != null }
        assertThat(state.isSubscribing).isFalse()
        assertThat(state.subscribedPodcastId).isNull()
        // The preview survives, so the screen still shows the show rather than going blank.
        assertThat(state.preview).isNotNull()
    }

    @Test
    fun subscribe_does_nothing_before_the_preview_has_loaded() = runTest(mainDispatcher.dispatcher) {
        host.enqueueStatus(404)
        val vm = viewModel(host.feedUrl())
        vm.state.awaitValue { it.error != null }

        vm.subscribe()

        awaitTrue("no subscription attempted") { graph.db.podcastDao().getAllIds().isEmpty() }
    }
}
