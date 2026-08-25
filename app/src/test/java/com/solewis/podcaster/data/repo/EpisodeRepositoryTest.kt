package com.solewis.podcaster.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.db.PodcasterDatabase
import com.solewis.podcaster.data.db.model.SortOrder
import com.solewis.podcaster.testing.episodeRow
import com.solewis.podcaster.testing.inMemoryDatabase
import com.solewis.podcaster.testing.podcastRow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [EpisodeRepository] is where a stored row becomes something the player can actually accept, and
 * the resume position is decided. Almost every assertion here is really about one question: does
 * playback start in the right place?
 */
@RunWith(AndroidJUnit4::class)
class EpisodeRepositoryTest {

    private lateinit var db: PodcasterDatabase
    private lateinit var repository: EpisodeRepository
    private var podcastId: Long = 0

    @Before
    fun setUp() = runTest {
        db = inMemoryDatabase()
        repository = EpisodeRepository(db.episodeDao(), db.podcastDao())
        podcastId = db.podcastDao().insert(podcastRow(title = "Show A"))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun a_part_listened_episode_resumes_at_its_saved_position() = runTest {
        db.episodeDao().insertNew(listOf(episodeRow(podcastId, "1", positionMillis = 120_000)))

        val playable = repository.getPlayableById("$podcastId:1")

        assertThat(playable?.startPositionMillis).isEqualTo(120_000)
    }

    @Test
    fun a_finished_episode_starts_over_rather_than_resuming_near_the_end() = runTest {
        // There is nothing left to resume to, and a stale near-the-end position would flash
        // briefly before the completion check corrected it.
        db.episodeDao().insertNew(
            listOf(episodeRow(podcastId, "1", positionMillis = 3_000_000, isPlayed = true))
        )

        val playable = repository.getPlayableById("$podcastId:1")

        assertThat(playable?.startPositionMillis).isEqualTo(0)
    }

    @Test
    fun an_episode_without_its_own_artwork_inherits_the_shows() = runTest {
        db.episodeDao().insertNew(listOf(episodeRow(podcastId, "1", artworkUrl = null)))

        val playable = repository.getPlayableById("$podcastId:1")

        // Most real feeds only set per-episode artwork occasionally; without this the notification
        // and Now Playing screen would show a blank image.
        assertThat(playable?.artworkUrl).isEqualTo("https://example.com/show.png")
        assertThat(playable?.podcastTitle).isEqualTo("Show A")
    }

    @Test
    fun an_episode_with_its_own_artwork_keeps_it() = runTest {
        db.episodeDao().insertNew(
            listOf(episodeRow(podcastId, "1", artworkUrl = "https://example.com/ep1.png"))
        )

        assertThat(repository.getPlayableById("$podcastId:1")?.artworkUrl)
            .isEqualTo("https://example.com/ep1.png")
    }

    @Test
    fun getPlayable_returns_null_for_an_unknown_episode() = runTest {
        assertThat(repository.getPlayableById("nope")).isNull()
    }

    @Test
    fun show_episodes_follow_the_sort_toggle_with_trailers_always_trailing() = runTest {
        db.episodeDao().insertNew(
            listOf(
                episodeRow(podcastId, "1"),
                episodeRow(podcastId, "2"),
                episodeRow(podcastId, "3"),
                episodeRow(podcastId, "t", chronoIndex = null, title = "Trailer")
            )
        )

        val newest = repository.getPlayableEpisodesForPodcast(podcastId, SortOrder.NEWEST_FIRST)
        val oldest = repository.getPlayableEpisodesForPodcast(podcastId, SortOrder.OLDEST_FIRST)

        assertThat(newest.map { it.title }).containsExactly("Episode 3", "Episode 2", "Episode 1", "Trailer").inOrder()
        // A trailer has no place in the chronology either way round, so it stays at the end
        // rather than jumping to the front when the toggle flips.
        assertThat(oldest.map { it.title }).containsExactly("Episode 1", "Episode 2", "Episode 3", "Trailer").inOrder()
    }

    @Test
    fun the_cross_show_feed_is_ordered_newest_published_first() = runTest {
        val otherId = db.podcastDao().insert(podcastRow(title = "Show B"))
        db.episodeDao().insertNew(
            listOf(
                episodeRow(podcastId, "1", title = "Older A", pubDateMillis = 1_000),
                episodeRow(otherId, "1", title = "Newer B", pubDateMillis = 5_000)
            )
        )

        val feed = repository.getPlayableEpisodesAcrossAllShows()

        assertThat(feed.map { it.title }).containsExactly("Newer B", "Older A").inOrder()
        assertThat(feed.map { it.podcastTitle }).containsExactly("Show B", "Show A").inOrder()
    }

    @Test
    fun next_in_show_is_the_following_unplayed_episode() = runTest {
        db.episodeDao().insertNew(
            listOf(
                episodeRow(podcastId, "1", isPlayed = true),
                episodeRow(podcastId, "2", isPlayed = true),
                episodeRow(podcastId, "3")
            )
        )

        val next = repository.getNextInShow("$podcastId:1")

        // Skips episode 2 because it's already been heard, rather than stopping at the first gap.
        assertThat(next?.title).isEqualTo("Episode 3")
    }

    @Test
    fun next_in_show_is_null_once_nothing_unplayed_remains() = runTest {
        db.episodeDao().insertNew(
            listOf(episodeRow(podcastId, "1"), episodeRow(podcastId, "2", isPlayed = true))
        )

        assertThat(repository.getNextInShow("$podcastId:1")).isNull()
    }

    @Test
    fun next_in_show_is_null_for_a_trailer_because_it_has_no_place_in_the_run() = runTest {
        db.episodeDao().insertNew(
            listOf(episodeRow(podcastId, "t", chronoIndex = null), episodeRow(podcastId, "1"))
        )

        assertThat(repository.getNextInShow("$podcastId:t")).isNull()
    }

    @Test
    fun backfilled_duration_is_recorded_as_exact() = runTest {
        db.episodeDao().insertNew(listOf(episodeRow(podcastId, "1", durationMillis = 100)))

        repository.backfillDuration("$podcastId:1", 987_000)

        val stored = db.episodeDao().getById("$podcastId:1")
        assertThat(stored?.durationMillis).isEqualTo(987_000)
        assertThat(stored?.durationIsExact).isTrue()
    }
}
