package com.solewis.podcaster.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.db.PodcasterDatabase
import com.solewis.podcaster.testing.episodeRow
import com.solewis.podcaster.testing.inMemoryDatabase
import com.solewis.podcaster.testing.podcastRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The queue's job is to answer one question correctly: what plays next. That answer feeds both
 * auto-advance on natural completion and the manual skip button, so a mistake here either stalls
 * playback in the car or jumps to the wrong episode.
 */
@RunWith(AndroidJUnit4::class)
class QueueRepositoryTest {

    private lateinit var db: PodcasterDatabase
    private lateinit var repository: QueueRepository
    private var podcastId: Long = 0

    @Before
    fun setUp() = runTest {
        db = inMemoryDatabase()
        repository = QueueRepository(db.queueDao(), EpisodeRepository(db.episodeDao(), db.podcastDao()))
        podcastId = db.podcastDao().insert(podcastRow())
        db.episodeDao().insertNew((1..4).map { episodeRow(podcastId, it.toString()) })
    }

    @After
    fun tearDown() = db.close()

    private suspend fun queuedTitles() = repository.observeQueue().first().map { it.title }

    @Test
    fun enqueue_appends_to_the_back() = runTest {
        repository.enqueue("$podcastId:2")
        repository.enqueue("$podcastId:1")
        repository.enqueue("$podcastId:3")

        assertThat(queuedTitles()).containsExactly("Episode 2", "Episode 1", "Episode 3").inOrder()
    }

    @Test
    fun moving_an_item_up_swaps_it_with_the_one_above() = runTest {
        repository.enqueue("$podcastId:1")
        repository.enqueue("$podcastId:2")
        val second = repository.observeQueue().first()[1]

        repository.moveUp(second.queueId)

        assertThat(queuedTitles()).containsExactly("Episode 2", "Episode 1").inOrder()
    }

    @Test
    fun moving_an_item_down_swaps_it_with_the_one_below() = runTest {
        repository.enqueue("$podcastId:1")
        repository.enqueue("$podcastId:2")
        val first = repository.observeQueue().first()[0]

        repository.moveDown(first.queueId)

        assertThat(queuedTitles()).containsExactly("Episode 2", "Episode 1").inOrder()
    }

    @Test
    fun moving_past_either_end_is_a_no_op_rather_than_an_error() = runTest {
        repository.enqueue("$podcastId:1")
        repository.enqueue("$podcastId:2")
        val items = repository.observeQueue().first()

        repository.moveUp(items.first().queueId)
        repository.moveDown(items.last().queueId)

        assertThat(queuedTitles()).containsExactly("Episode 1", "Episode 2").inOrder()
    }

    @Test
    fun reordering_renumbers_positions_contiguously_so_later_moves_still_work() = runTest {
        (1..3).forEach { repository.enqueue("$podcastId:$it") }
        val items = repository.observeQueue().first()

        repository.moveDown(items[0].queueId)

        assertThat(db.queueDao().getAllOrdered().map { it.position }).containsExactly(0, 1, 2).inOrder()
        assertThat(queuedTitles()).containsExactly("Episode 2", "Episode 1", "Episode 3").inOrder()
    }

    @Test
    fun next_playable_pops_the_front_of_the_queue() = runTest {
        repository.enqueue("$podcastId:3")
        repository.enqueue("$podcastId:4")

        val next = repository.nextPlayable(currentEpisodeId = "$podcastId:1")

        assertThat(next?.title).isEqualTo("Episode 3")
        // Popped, not just read - otherwise the same episode would replay forever.
        assertThat(queuedTitles()).containsExactly("Episode 4")
    }

    @Test
    fun next_playable_resumes_a_queued_episode_at_its_saved_position() = runTest {
        db.episodeDao().setProgress("$podcastId:3", positionMillis = 60_000, isPlayed = false, now = 1L)
        repository.enqueue("$podcastId:3")

        val next = repository.nextPlayable(currentEpisodeId = "$podcastId:1")

        // Reaching an episode through the queue must not lose its resume point.
        assertThat(next?.startPositionMillis).isEqualTo(60_000)
    }

    @Test
    fun an_empty_queue_falls_through_to_the_next_unplayed_episode_in_the_same_show() = runTest {
        db.episodeDao().setProgress("$podcastId:2", positionMillis = 0, isPlayed = true, now = 1L)

        val next = repository.nextPlayable(currentEpisodeId = "$podcastId:1")

        assertThat(next?.title).isEqualTo("Episode 3")
    }

    @Test
    fun next_playable_is_null_when_the_queue_is_empty_and_nothing_is_playing() = runTest {
        assertThat(repository.nextPlayable(currentEpisodeId = null)).isNull()
    }

    @Test
    fun unsubscribing_a_show_takes_its_episodes_out_of_the_queue() = runTest {
        val otherId = db.podcastDao().insert(podcastRow(title = "Other Show"))
        db.episodeDao().insertNew(listOf(episodeRow(otherId, "9", title = "Keep Me")))
        repository.enqueue("$podcastId:1")
        repository.enqueue("$otherId:9")

        db.podcastDao().delete(podcastId)

        // Two cascades deep: deleting the show removes its episodes, which removes their queue
        // rows. Without that the queue would keep pointing at episodes that no longer exist.
        assertThat(queuedTitles()).containsExactly("Keep Me")
        assertThat(db.queueDao().getAllOrdered()).hasSize(1)
    }

    @Test
    fun enqueuing_the_same_episode_twice_does_not_create_a_duplicate() = runTest {
        repository.enqueue("$podcastId:1")
        repository.enqueue("$podcastId:1")

        assertThat(db.queueDao().getAllOrdered()).hasSize(1)
    }

    @Test
    fun remove_drops_only_the_requested_item() = runTest {
        repository.enqueue("$podcastId:1")
        repository.enqueue("$podcastId:2")
        val first = repository.observeQueue().first().first()

        repository.remove(first.queueId)

        assertThat(queuedTitles()).containsExactly("Episode 2")
    }

    @Test
    fun the_playable_snapshot_follows_queue_order() = runTest {
        repository.enqueue("$podcastId:4")
        repository.enqueue("$podcastId:2")

        assertThat(repository.getPlayableQueue().map { it.title })
            .containsExactly("Episode 4", "Episode 2").inOrder()
    }
}
