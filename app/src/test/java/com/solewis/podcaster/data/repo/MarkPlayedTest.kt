package com.solewis.podcaster.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.db.PodcasterDatabase
import com.solewis.podcaster.domain.JumpTargetResolver
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
 * Marking played by hand, and what it does to the jump pill - which is the part with a real trap in
 * it. The pill anchors on the greatest `lastPlayedAt`, so whether a mark stamps that column decides
 * where the pill points afterwards.
 */
@RunWith(AndroidJUnit4::class)
class MarkPlayedTest {

    private lateinit var db: PodcasterDatabase
    private lateinit var repository: EpisodeRepository
    private var podcastId: Long = 0
    private var clock = 10_000L

    @Before
    fun setUp() = runTest {
        db = inMemoryDatabase()
        repository = EpisodeRepository(db.episodeDao(), db.podcastDao()) { clock }
        podcastId = db.podcastDao().insert(podcastRow(title = "Radiolab"))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun episodes() = db.episodeDao().observeListForPodcast(podcastId).first()

    private suspend fun episode(key: String) = episodes().first { it.id == "$podcastId:$key" }

    @Test
    fun marking_played_records_it_as_finished() = runTest {
        db.episodeDao().insertNew(listOf(episodeRow(podcastId, "1", durationMillis = 600_000)))

        repository.markPlayed("$podcastId:1")

        assertThat(episode("1").isPlayed).isTrue()
    }

    @Test
    fun marking_played_moves_the_jump_pill_on_to_the_next_episode() = runTest {
        db.episodeDao().insertNew(
            listOf(episodeRow(podcastId, "1"), episodeRow(podcastId, "2"), episodeRow(podcastId, "3"))
        )

        repository.markPlayed("$podcastId:1")

        // The whole point of the action: having declared episode 1 done, the show should offer
        // episode 2. That only works because the mark stamps lastPlayedAt, which is what the
        // resolver anchors on - without it the pill would ignore the mark entirely.
        val target = JumpTargetResolver.resolve(episodes())
        assertThat(target?.intent).isEqualTo(JumpTargetResolver.Intent.NEXT)
        assertThat(target?.episodeId).isEqualTo("$podcastId:2")
    }

    @Test
    fun marking_unplayed_forgets_the_position_as_well_as_the_flag() = runTest {
        db.episodeDao().insertNew(
            listOf(episodeRow(podcastId, "1", positionMillis = 400_000, isPlayed = true, lastPlayedAt = 5_000))
        )

        repository.markUnplayed("$podcastId:1")

        with(episode("1")) {
            assertThat(isPlayed).isFalse()
            // "Unplayed" that still resumed three quarters of the way in would be a lie.
            assertThat(positionMillis).isEqualTo(0)
            assertThat(lastPlayedAt).isNull()
        }
    }

    @Test
    fun marking_unplayed_stops_the_pill_offering_to_resume_mid_episode() = runTest {
        db.episodeDao().insertNew(
            listOf(
                episodeRow(podcastId, "1", isPlayed = true, lastPlayedAt = 5_000),
                episodeRow(podcastId, "2", positionMillis = 60_000, lastPlayedAt = 9_000)
            )
        )
        // Episode 2 is part-listened, so the pill resumes it a minute in.
        assertThat(JumpTargetResolver.resolve(episodes())?.intent)
            .isEqualTo(JumpTargetResolver.Intent.RESUME)

        repository.markUnplayed("$podcastId:2")

        // Clearing lastPlayedAt hands the anchor back to episode 1, which is finished - so the pill
        // still points at episode 2, but now offers it from the start rather than a minute in.
        val target = JumpTargetResolver.resolve(episodes())
        assertThat(target?.episodeId).isEqualTo("$podcastId:2")
        assertThat(target?.intent).isEqualTo(JumpTargetResolver.Intent.NEXT)
    }

    @Test
    fun marking_a_show_played_reports_how_many_it_changed() = runTest {
        db.episodeDao().insertNew(
            listOf(
                episodeRow(podcastId, "1", isPlayed = true),
                episodeRow(podcastId, "2"),
                episodeRow(podcastId, "3")
            )
        )

        // Only the two unplayed ones - the count is shown to the user, so counting the already
        // finished one would overstate what happened.
        assertThat(repository.markAllPlayed(podcastId)).isEqualTo(2)
        assertThat(episodes().all { it.isPlayed }).isTrue()
    }

    @Test
    fun marking_a_show_played_leaves_the_jump_pill_where_your_listening_left_it() = runTest {
        db.episodeDao().insertNew(
            listOf(
                episodeRow(podcastId, "1", isPlayed = true, lastPlayedAt = 5_000),
                episodeRow(podcastId, "2"),
                episodeRow(podcastId, "3")
            )
        )

        repository.markAllPlayed(podcastId)

        // Stamping lastPlayedAt on every row would leave the resolver choosing between identical
        // timestamps, making the pill's target depend on row order. Episode 1 is what was actually
        // listened to, so it stays the anchor - and with nothing unplayed left, the pill revisits it.
        val target = JumpTargetResolver.resolve(episodes())
        assertThat(target?.intent).isEqualTo(JumpTargetResolver.Intent.REVISIT)
        assertThat(target?.episodeId).isEqualTo("$podcastId:1")
    }

    @Test
    fun marking_a_show_played_does_not_invent_a_listening_history() = runTest {
        db.episodeDao().insertNew(listOf(episodeRow(podcastId, "1"), episodeRow(podcastId, "2")))

        repository.markAllPlayed(podcastId)

        // Nothing was ever played here, so there is nothing to resume, jump to, or revisit.
        assertThat(JumpTargetResolver.resolve(episodes())).isNull()
    }

    @Test
    fun marking_a_show_played_leaves_other_shows_alone() = runTest {
        val other = db.podcastDao().insert(podcastRow(title = "Other Show"))
        db.episodeDao().insertNew(listOf(episodeRow(podcastId, "1")))
        db.episodeDao().insertNew(listOf(episodeRow(other, "1")))

        repository.markAllPlayed(podcastId)

        assertThat(db.episodeDao().observeListForPodcast(other).first().single().isPlayed).isFalse()
    }

    @Test
    fun a_played_episode_starts_over_rather_than_resuming_at_the_end() = runTest {
        db.episodeDao().insertNew(
            listOf(episodeRow(podcastId, "1", positionMillis = 300_000, durationMillis = 600_000))
        )

        repository.markPlayed("$podcastId:1")

        // markPlayed leaves positionMillis alone, so this is the existing "finished episodes start
        // from 0" rule doing the work - worth pinning, since otherwise a marked episode would
        // resume wherever playback happened to have reached.
        assertThat(repository.getPlayableById("$podcastId:1")?.startPositionMillis).isEqualTo(0)
    }
}
