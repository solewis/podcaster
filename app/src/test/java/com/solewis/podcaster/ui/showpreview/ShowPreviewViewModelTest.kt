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

    private fun viewModel(feedUrl: String, seedTitle: String? = null) = graph.hosting(
        ShowPreviewViewModel(
        feedUrl = feedUrl,
        itunesCollectionId = 7,
        seedTitle = seedTitle,
        seedArtworkUrl = null,
        showPreviewRepository = ShowPreviewRepository(FeedFetcher()),
        subscriptionRepository = graph.subscriptionRepository,
        podcastRepository = graph.podcastRepository,
        playback = graph.playback,
        playbackStarter = graph.playbackStarter
        )
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
    fun subscribing_from_the_preview_does_not_download_the_feed_again() = runTest(mainDispatcher.dispatcher) {
        host.enqueueFeed("serial_with_episode_numbers.xml")
        val vm = viewModel(host.feedUrl())
        vm.state.awaitValue { it.preview != null }

        vm.subscribe()

        val state = vm.state.awaitValue { it.subscribedPodcastId != null }
        val podcastId = state.subscribedPodcastId!!
        graph.subscriptionRepository.awaitLoad(podcastId)
        assertThat(state.isSubscribing).isFalse()
        assertThat(graph.db.podcastDao().getById(podcastId)?.title).isEqualTo("A Serial Audio Drama")
        assertThat(graph.db.episodeDao().getAllForPodcast(podcastId)).isNotEmpty()
        // The preview's fetch was handed over. It used to be thrown away and the whole feed - 25MB
        // for some shows - downloaded again.
        assertThat(host.requestCount).isEqualTo(1)
    }

    @Test
    fun the_show_is_named_before_its_feed_arrives() = runTest(mainDispatcher.dispatcher) {
        host.enqueueFeed("serial_with_episode_numbers.xml", delayMillis = 1_000)

        val vm = viewModel(host.feedUrl(), seedTitle = "From Search")

        // Only the episode list waits on the feed; the header has what Search already knew.
        val state = vm.state.value
        assertThat(state.title).isEqualTo("From Search")
        assertThat(state.isLoading).isTrue()
        assertThat(vm.state.awaitValue { it.preview != null }.title).isEqualTo("A Serial Audio Drama")
    }

    @Test
    fun subscribing_does_not_wait_for_the_episodes() = runTest(mainDispatcher.dispatcher) {
        host.enqueueFeed("serial_with_episode_numbers.xml", delayMillis = 2_000)
        host.enqueueFeed("serial_with_episode_numbers.xml", delayMillis = 2_000)
        val vm = viewModel(host.feedUrl(), seedTitle = "From Search")

        vm.subscribe()

        val state = vm.state.awaitValue { it.subscribedPodcastId != null }
        // Still nothing from the feed: the show was added with what Search knew, and its episodes
        // are loading in the background.
        assertThat(state.preview).isNull()
        assertThat(graph.db.podcastDao().getById(state.subscribedPodcastId!!)?.title).isEqualTo("From Search")
    }
}
