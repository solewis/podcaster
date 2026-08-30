package com.solewis.podcaster.ui.downloads

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.repo.DownloadStatus
import com.solewis.podcaster.testing.MainDispatcherRule
import com.solewis.podcaster.testing.TestGraph
import com.solewis.podcaster.testing.awaitTrue
import com.solewis.podcaster.testing.awaitValue
import com.solewis.podcaster.testing.episodeRow
import com.solewis.podcaster.testing.keepHot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Downloads screen has to join two sources that know nothing about each other: Media3's
 * download index, which holds ids and byte counts and has never heard of an episode title, and
 * Room, which has the titles. Everything below is about that join being right, and about the
 * ordering being the one that suits why someone opens this screen.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private lateinit var graph: TestGraph
    private var podcastId: Long = 0

    @Before
    fun setUp() = runTest {
        graph = TestGraph()
        podcastId = graph.insertShow(title = "Radiolab")
    }

    @After
    fun tearDown() = graph.close()

    private fun viewModel() = graph.hosting(
        DownloadsViewModel(graph.episodeRepository, graph.downloads, graph.playback, graph.playbackStarter)
    )

    @Test
    fun a_download_is_shown_with_the_episode_it_belongs_to() = runTest(mainDispatcher.dispatcher) {
        graph.insertEpisodes(episodeRow(podcastId, "1", title = "Patient Zero"))
        graph.downloads.emit("$podcastId:1", DownloadStatus.DOWNLOADED, bytesDownloaded = 40 * 1024 * 1024)
        val vm = viewModel()
        keepHot(vm.rows)

        val rows = vm.rows.awaitValue { it.isNotEmpty() }

        assertThat(rows.single().episode.title).isEqualTo("Patient Zero")
        assertThat(rows.single().episode.podcastTitle).isEqualTo("Radiolab")
        assertThat(rows.single().download.status).isEqualTo(DownloadStatus.DOWNLOADED)
    }

    @Test
    fun an_episode_with_no_download_is_not_listed() = runTest(mainDispatcher.dispatcher) {
        graph.insertEpisodes(episodeRow(podcastId, "1"), episodeRow(podcastId, "2"))
        graph.downloads.emit("$podcastId:2", DownloadStatus.DOWNLOADED)
        val vm = viewModel()
        keepHot(vm.rows)

        val rows = vm.rows.awaitValue { it.isNotEmpty() }

        assertThat(rows.map { it.episode.id }).containsExactly("$podcastId:2")
    }

    @Test
    fun a_download_whose_show_was_unsubscribed_is_dropped_rather_than_shown_blank() =
        runTest(mainDispatcher.dispatcher) {
            // Media3's index outlives the Room rows, so this state is reachable: the file is still
            // on disk with nothing left to label it. Rendering it would mean a row with no title.
            graph.downloads.emit("$podcastId:gone", DownloadStatus.DOWNLOADED)
            val vm = viewModel()
            keepHot(vm.rows)

            awaitTrue("the orphan was not rendered") { vm.rows.value.isEmpty() }
        }

    @Test
    fun anything_still_downloading_sorts_above_finished_downloads() =
        runTest(mainDispatcher.dispatcher) {
            graph.insertEpisodes(
                episodeRow(podcastId, "1", title = "Finished and large"),
                episodeRow(podcastId, "2", title = "Still going")
            )
            // The finished one is far bigger, so size alone would put it first.
            graph.downloads.emit("$podcastId:1", DownloadStatus.DOWNLOADED, bytesDownloaded = 90_000_000)
            graph.downloads.emit("$podcastId:2", DownloadStatus.DOWNLOADING, percent = 10f, bytesDownloaded = 900_000)
            val vm = viewModel()
            keepHot(vm.rows)

            val rows = vm.rows.awaitValue { it.size == 2 }

            assertThat(rows.map { it.episode.title })
                .containsExactly("Still going", "Finished and large").inOrder()
        }

    @Test
    fun finished_downloads_are_ordered_biggest_first() = runTest(mainDispatcher.dispatcher) {
        graph.insertEpisodes(
            episodeRow(podcastId, "1", title = "Small"),
            episodeRow(podcastId, "2", title = "Large")
        )
        graph.downloads.emit("$podcastId:1", DownloadStatus.DOWNLOADED, bytesDownloaded = 10_000_000)
        graph.downloads.emit("$podcastId:2", DownloadStatus.DOWNLOADED, bytesDownloaded = 80_000_000)
        val vm = viewModel()
        keepHot(vm.rows)

        val rows = vm.rows.awaitValue { it.size == 2 }

        // Someone on this screen to free space wants the biggest first.
        assertThat(rows.map { it.episode.title }).containsExactly("Large", "Small").inOrder()
    }

    @Test
    fun the_running_total_comes_from_the_disk_not_from_the_rows() =
        runTest(mainDispatcher.dispatcher) {
            graph.insertEpisodes(episodeRow(podcastId, "1"))
            graph.downloads.bytesOnDisk = 123_456_789
            graph.downloads.emit("$podcastId:1", DownloadStatus.DOWNLOADED, bytesDownloaded = 1_000)
            val vm = viewModel()
            keepHot(vm.rows, vm.totalBytes)

            // Summing the rows would under-report by everything whose show has been unsubscribed,
            // and then disagree with the phone's own storage screen.
            assertThat(vm.totalBytes.awaitValue { it > 0 }).isEqualTo(123_456_789)
        }

    @Test
    fun deleting_a_download_asks_for_exactly_that_episode() = runTest(mainDispatcher.dispatcher) {
        graph.insertEpisodes(episodeRow(podcastId, "1"))
        val vm = viewModel()

        vm.remove("$podcastId:1")

        awaitTrue("removal requested") { graph.downloads.removed == listOf("$podcastId:1") }
    }

    @Test
    fun retrying_a_failed_download_asks_for_it_again() = runTest(mainDispatcher.dispatcher) {
        graph.insertEpisodes(episodeRow(podcastId, "1"))
        val vm = viewModel()

        vm.retry("$podcastId:1")

        awaitTrue("download requested") { graph.downloads.requested == listOf("$podcastId:1") }
    }

    @Test
    fun playing_from_the_downloads_list_hands_over_the_real_episode() =
        runTest(mainDispatcher.dispatcher) {
            graph.insertEpisodes(episodeRow(podcastId, "1", positionMillis = 30_000))
            val vm = viewModel()

            vm.play("$podcastId:1")

            awaitTrue("episode handed to playback") { graph.playback.played.isNotEmpty() }
            with(graph.playback.played.single()) {
                assertThat(episodeId).isEqualTo("$podcastId:1")
                // Downloaded or not, a part-listened episode still resumes where it was left.
                assertThat(startPositionMillis).isEqualTo(30_000)
            }
        }
}
