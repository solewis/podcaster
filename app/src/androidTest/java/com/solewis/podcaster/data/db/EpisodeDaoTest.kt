package com.solewis.podcaster.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.db.entity.EpisodeEntity
import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.data.db.model.SortOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpisodeDaoTest {

    private lateinit var db: PodcasterDatabase
    private lateinit var podcastDao: PodcastDao
    private lateinit var episodeDao: EpisodeDao
    private var podcastId: Long = 0

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PodcasterDatabase::class.java)
            .build()
        podcastDao = db.podcastDao()
        episodeDao = db.episodeDao()
        podcastId = podcastDao.insert(
            PodcastEntity(feedUrl = "https://example.com/feed.xml", title = "Test Show", subscribedAt = 1000L)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun episode(
        id: String,
        feedPosition: Int,
        chronoIndex: Int? = feedPosition,
        title: String = "Episode $feedPosition",
        positionMillis: Long = 0,
        isPlayed: Boolean = false,
        lastPlayedAt: Long? = null
    ) = EpisodeEntity(
        id = id,
        podcastId = podcastId,
        stableKey = id,
        stableKeySource = "guid",
        title = title,
        enclosureUrl = "https://example.com/$id.mp3",
        feedPosition = feedPosition,
        chronoIndex = chronoIndex,
        displayNumber = chronoIndex,
        positionMillis = positionMillis,
        isPlayed = isPlayed,
        lastPlayedAt = lastPlayedAt,
        firstSeenAt = 1000L
    )

    @Test
    fun refresh_does_not_clobber_progress() = runTest {
        // Arrange: an episode with real listening history, as if the user had played it.
        episodeDao.insertNew(listOf(episode(id = "ep1", feedPosition = 0)))
        episodeDao.updateMetadata(
            id = "ep1", title = "Episode 0", descriptionHtml = null, pubDateMillis = null,
            enclosureUrl = "https://example.com/ep1.mp3", enclosureBytes = null, enclosureMimeType = null,
            artworkUrl = null, itunesEpisodeNumber = null, itunesSeason = null, episodeType = "full",
            webPageUrl = null, feedPosition = 0, chronoIndex = 1, displayNumber = 1, durationMillis = 1_000_000L
        )
        // Simulate real playback directly at the SQL layer (no player exists yet in this phase).
        db.openHelper.writableDatabase.execSQL(
            "UPDATE episodes SET positionMillis = 45000, isPlayed = 0, lastPlayedAt = 5000 WHERE id = 'ep1'"
        )

        val beforeRefresh = episodeDao.getById("ep1")!!
        assertThat(beforeRefresh.positionMillis).isEqualTo(45000L)
        assertThat(beforeRefresh.lastPlayedAt).isEqualTo(5000L)

        // Act: a feed refresh re-processes the same episode - this must NOT be an upsert/replace.
        val insertedRowIds = episodeDao.insertNew(listOf(episode(id = "ep1", feedPosition = 0)))
        episodeDao.updateMetadata(
            id = "ep1", title = "Episode 0 (updated title)", descriptionHtml = "<p>New notes</p>",
            pubDateMillis = 2000L, enclosureUrl = "https://example.com/ep1-v2.mp3", enclosureBytes = 999L,
            enclosureMimeType = "audio/mpeg", artworkUrl = "https://example.com/art.jpg",
            itunesEpisodeNumber = null, itunesSeason = null, episodeType = "full", webPageUrl = null,
            feedPosition = 0, chronoIndex = 1, displayNumber = 1, durationMillis = 1_500_000L
        )

        // Assert: metadata changed...
        val afterRefresh = episodeDao.getById("ep1")!!
        assertThat(afterRefresh.title).isEqualTo("Episode 0 (updated title)")
        assertThat(afterRefresh.enclosureUrl).isEqualTo("https://example.com/ep1-v2.mp3")

        // ...but every playback column survived untouched. This is the whole point of the app.
        assertThat(afterRefresh.positionMillis).isEqualTo(45000L)
        assertThat(afterRefresh.isPlayed).isFalse()
        assertThat(afterRefresh.lastPlayedAt).isEqualTo(5000L)

        // The re-insert of an already-known id must have been ignored (rowid -1), not replaced.
        assertThat(insertedRowIds).containsExactly(-1L)
    }

    @Test
    fun refresh_does_not_override_duration_once_player_has_reported_an_exact_one() = runTest {
        episodeDao.insertNew(listOf(episode(id = "ep1", feedPosition = 0)))
        // Simulate the player having backfilled a real duration.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE episodes SET durationMillis = 1234000, durationIsExact = 1 WHERE id = 'ep1'"
        )

        // A refresh brings a DIFFERENT (e.g. wrong/lying) duration from the feed.
        episodeDao.updateMetadata(
            id = "ep1", title = "Episode 0", descriptionHtml = null, pubDateMillis = null,
            enclosureUrl = "https://example.com/ep1.mp3", enclosureBytes = null, enclosureMimeType = null,
            artworkUrl = null, itunesEpisodeNumber = null, itunesSeason = null, episodeType = "full",
            webPageUrl = null, feedPosition = 0, chronoIndex = 1, displayNumber = 1, durationMillis = 999L
        )

        assertThat(episodeDao.getById("ep1")!!.durationMillis).isEqualTo(1234000L)
    }

    @Test
    fun refresh_does_apply_feed_duration_when_not_yet_confirmed_exact() = runTest {
        episodeDao.insertNew(listOf(episode(id = "ep1", feedPosition = 0)))

        episodeDao.updateMetadata(
            id = "ep1", title = "Episode 0", descriptionHtml = null, pubDateMillis = null,
            enclosureUrl = "https://example.com/ep1.mp3", enclosureBytes = null, enclosureMimeType = null,
            artworkUrl = null, itunesEpisodeNumber = null, itunesSeason = null, episodeType = "full",
            webPageUrl = null, feedPosition = 0, chronoIndex = 1, displayNumber = 1, durationMillis = 42_000L
        )

        assertThat(episodeDao.getById("ep1")!!.durationMillis).isEqualTo(42_000L)
    }

    @Test
    fun getLastListened_returns_the_episode_with_the_most_recent_lastPlayedAt() = runTest {
        episodeDao.insertNew(
            listOf(
                episode(id = "ep1", feedPosition = 0, lastPlayedAt = 1000L),
                episode(id = "ep2", feedPosition = 1, lastPlayedAt = 5000L),
                episode(id = "ep3", feedPosition = 2, lastPlayedAt = 3000L)
            )
        )

        val lastListened = episodeDao.getLastListened(podcastId)
        assertThat(lastListened?.id).isEqualTo("ep2")
    }

    @Test
    fun getLastListened_ignores_episodes_that_have_never_been_played() = runTest {
        episodeDao.insertNew(listOf(episode(id = "ep1", feedPosition = 0, lastPlayedAt = null)))
        assertThat(episodeDao.getLastListened(podcastId)).isNull()
    }

    @Test
    fun getLastListened_is_scoped_to_the_requested_podcast() = runTest {
        val otherPodcastId = podcastDao.insert(
            PodcastEntity(feedUrl = "https://example.com/other.xml", title = "Other Show", subscribedAt = 1000L)
        )
        episodeDao.insertNew(listOf(episode(id = "ep1", feedPosition = 0, lastPlayedAt = 9000L)))
        episodeDao.insertNew(
            listOf(
                EpisodeEntity(
                    id = "other-ep1", podcastId = otherPodcastId, stableKey = "other-ep1",
                    stableKeySource = "guid", title = "Other episode",
                    enclosureUrl = "https://example.com/other-ep1.mp3", feedPosition = 0,
                    chronoIndex = 1, displayNumber = 1, lastPlayedAt = 20000L, firstSeenAt = 1000L
                )
            )
        )

        assertThat(episodeDao.getLastListened(podcastId)?.id).isEqualTo("ep1")
    }

    @Test
    fun deleteIfNeverPlayed_removes_vanished_unplayed_episodes_but_preserves_listen_history() = runTest {
        episodeDao.insertNew(
            listOf(
                episode(id = "ep1", feedPosition = 0, lastPlayedAt = null),
                episode(id = "ep2", feedPosition = 1, lastPlayedAt = 5000L)
            )
        )

        // Both episodes vanished from a refreshed feed - but ep2 has listen history.
        episodeDao.deleteIfNeverPlayed(podcastId, listOf("ep1", "ep2"))

        assertThat(episodeDao.getById("ep1")).isNull()
        assertThat(episodeDao.getById("ep2")).isNotNull()
    }

    @Test
    fun deleting_a_podcast_cascades_to_delete_its_episodes() = runTest {
        episodeDao.insertNew(listOf(episode(id = "ep1", feedPosition = 0)))
        podcastDao.delete(podcastId)
        assertThat(episodeDao.getById("ep1")).isNull()
    }

    @Test
    fun observeListForPodcast_orders_newest_first_by_chronoIndex() = runTest {
        episodeDao.insertNew(
            listOf(
                episode(id = "ep1", feedPosition = 0, chronoIndex = 1),
                episode(id = "ep2", feedPosition = 1, chronoIndex = 3),
                episode(id = "ep3", feedPosition = 2, chronoIndex = 2)
            )
        )

        val result = episodeDao.observeListForPodcast(podcastId).first().map { it.id }

        assertThat(result).containsExactly("ep2", "ep3", "ep1").inOrder()
    }

    @Test
    fun setProgress_records_position_and_stamps_lastPlayedAt_as_the_jump_anchor() = runTest {
        episodeDao.insertNew(listOf(episode(id = "ep1", feedPosition = 0)))

        episodeDao.setProgress(id = "ep1", positionMillis = 12_345L, isPlayed = false, now = 9999L)

        val updated = episodeDao.getById("ep1")!!
        assertThat(updated.positionMillis).isEqualTo(12_345L)
        assertThat(updated.isPlayed).isFalse()
        assertThat(updated.lastPlayedAt).isEqualTo(9999L)
        assertThat(updated.playedAt).isNull()
    }

    @Test
    fun setProgress_marking_played_stamps_playedAt_too() = runTest {
        episodeDao.insertNew(listOf(episode(id = "ep1", feedPosition = 0)))

        episodeDao.setProgress(id = "ep1", positionMillis = 0L, isPlayed = true, now = 5000L)

        val updated = episodeDao.getById("ep1")!!
        assertThat(updated.isPlayed).isTrue()
        assertThat(updated.playedAt).isEqualTo(5000L)
        assertThat(updated.lastPlayedAt).isEqualTo(5000L)
    }

    @Test
    fun setSortOrder_persists_the_per_show_toggle() = runTest {
        assertThat(podcastDao.getById(podcastId)!!.sortOrder).isEqualTo(SortOrder.NEWEST_FIRST)

        podcastDao.setSortOrder(podcastId, SortOrder.OLDEST_FIRST)

        assertThat(podcastDao.getById(podcastId)!!.sortOrder).isEqualTo(SortOrder.OLDEST_FIRST)
    }

    @Test
    fun backfillDuration_sets_duration_and_marks_it_exact() = runTest {
        episodeDao.insertNew(listOf(episode(id = "ep1", feedPosition = 0)))
        assertThat(episodeDao.getById("ep1")!!.durationIsExact).isFalse()

        episodeDao.backfillDuration("ep1", 1_234_000L)

        val updated = episodeDao.getById("ep1")!!
        assertThat(updated.durationMillis).isEqualTo(1_234_000L)
        assertThat(updated.durationIsExact).isTrue()
    }

    @Test
    fun backfillDuration_can_correct_an_already_exact_value_that_actually_changed() = runTest {
        episodeDao.insertNew(listOf(episode(id = "ep1", feedPosition = 0)))
        episodeDao.backfillDuration("ep1", 1_000_000L)

        // The player reports a different (more accurate) duration on a later playback.
        episodeDao.backfillDuration("ep1", 1_050_000L)

        assertThat(episodeDao.getById("ep1")!!.durationMillis).isEqualTo(1_050_000L)
    }
}
