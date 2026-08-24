package com.solewis.podcaster.player

import androidx.media3.common.MediaItem
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.db.PodcasterDatabase
import com.solewis.podcaster.data.db.entity.EpisodeEntity
import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.data.db.entity.QueueEntity
import com.solewis.podcaster.data.db.model.SortOrder
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.PodcastRepository
import com.solewis.podcaster.data.repo.QueueRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the actual Android Auto browse-tree logic against a real (in-memory) Room database,
 * the same way `EpisodeDaoTest` verifies the DAO layer - deliberately not a full
 * `MediaBrowser`/`MediaSession` binder round-trip, since [PodcastLibraryTree] is plain Kotlin
 * over the same repositories the app's own UI uses and doesn't need one.
 *
 * The resume-position test is the one that matters most: without it, tapping an episode from
 * Auto's browse tree would silently restart from 0 instead of resuming, defeating the entire
 * point of this app.
 */
@RunWith(AndroidJUnit4::class)
class PodcastLibraryTreeTest {

    private lateinit var db: PodcasterDatabase
    private lateinit var tree: PodcastLibraryTree
    private var podcastId: Long = 0

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PodcasterDatabase::class.java)
            .build()
        val episodeRepository = EpisodeRepository(db.episodeDao(), db.podcastDao())
        tree = PodcastLibraryTree(
            podcastRepository = PodcastRepository(db.podcastDao()),
            episodeRepository = episodeRepository,
            queueRepository = QueueRepository(db.queueDao(), episodeRepository)
        )
        podcastId = db.podcastDao().insert(
            PodcastEntity(feedUrl = "https://example.com/feed.xml", title = "Test Show", subscribedAt = 1000L)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun episode(
        id: String,
        chronoIndex: Int?,
        title: String = "Episode $chronoIndex",
        positionMillis: Long = 0,
        isPlayed: Boolean = false,
        podcastIdOverride: Long = podcastId,
        pubDateMillis: Long? = null
    ) = EpisodeEntity(
        id = id,
        podcastId = podcastIdOverride,
        stableKey = id,
        stableKeySource = "guid",
        title = title,
        enclosureUrl = "https://example.com/$id.mp3",
        feedPosition = chronoIndex ?: 0,
        chronoIndex = chronoIndex,
        displayNumber = chronoIndex,
        positionMillis = positionMillis,
        isPlayed = isPlayed,
        pubDateMillis = pubDateMillis,
        firstSeenAt = 1000L
    )

    @Test
    fun rootChildren_is_a_fixed_three_tabs_regardless_of_subscription_count() = runTest {
        // Android Auto renders root's direct children as a persistent top tab strip - this must
        // stay exactly three tabs no matter how many shows are subscribed, or it stops scaling.
        db.podcastDao().insert(
            PodcastEntity(feedUrl = "https://example.com/other.xml", title = "Other Show", subscribedAt = 1000L)
        )

        val root = tree.children(PodcastLibraryTree.ROOT_ID)

        assertThat(root.map { it.mediaId }).containsExactly(
            PodcastLibraryTree.QUEUE_ID,
            PodcastLibraryTree.EPISODES_ID,
            PodcastLibraryTree.SUBSCRIPTIONS_ID
        )
        assertThat(root.all { it.mediaMetadata.isBrowsable == true }).isTrue()
    }

    @Test
    fun subscriptions_children_include_every_subscribed_podcast() = runTest {
        db.podcastDao().insert(
            PodcastEntity(feedUrl = "https://example.com/other.xml", title = "Other Show", subscribedAt = 1000L)
        )

        val subscriptions = tree.children(PodcastLibraryTree.SUBSCRIPTIONS_ID)

        assertThat(subscriptions.map { it.mediaId }).containsExactly(
            "${PodcastLibraryTree.PODCAST_PREFIX}$podcastId",
            "${PodcastLibraryTree.PODCAST_PREFIX}2"
        )
        assertThat(subscriptions.all { it.mediaMetadata.isBrowsable == true }).isTrue()
    }

    @Test
    fun episodes_children_span_every_show_newest_published_first() = runTest {
        val otherPodcastId = db.podcastDao().insert(
            PodcastEntity(feedUrl = "https://example.com/other.xml", title = "Other Show", subscribedAt = 1000L)
        )
        db.episodeDao().insertNew(
            listOf(
                episode(id = "ep1", chronoIndex = 1, pubDateMillis = 1000L),
                episode(id = "ep2", chronoIndex = 2, pubDateMillis = 3000L),
                episode(
                    id = "other-ep1",
                    chronoIndex = 1,
                    pubDateMillis = 2000L,
                    podcastIdOverride = otherPodcastId
                )
            )
        )

        val episodes = tree.children(PodcastLibraryTree.EPISODES_ID)

        assertThat(episodes.map { it.mediaId }).containsExactly("ep2", "other-ep1", "ep1").inOrder()
        assertThat(episodes.all { it.mediaMetadata.isPlayable == true }).isTrue()
        assertThat(episodes.all { it.localConfiguration?.uri != null }).isTrue()
    }

    @Test
    fun podcast_children_are_playable_episodes_newest_first_by_default() = runTest {
        db.episodeDao().insertNew(
            listOf(
                episode(id = "ep1", chronoIndex = 1),
                episode(id = "ep2", chronoIndex = 3),
                episode(id = "ep3", chronoIndex = 2)
            )
        )

        val children = tree.children("${PodcastLibraryTree.PODCAST_PREFIX}$podcastId")

        assertThat(children.map { it.mediaId }).containsExactly("ep2", "ep3", "ep1").inOrder()
        assertThat(children.all { it.mediaMetadata.isPlayable == true }).isTrue()
        assertThat(children.all { it.localConfiguration?.uri != null }).isTrue()
    }

    @Test
    fun podcast_children_respect_the_shows_stored_sort_order() = runTest {
        db.podcastDao().setSortOrder(podcastId, SortOrder.OLDEST_FIRST)
        db.episodeDao().insertNew(
            listOf(episode(id = "ep1", chronoIndex = 1), episode(id = "ep2", chronoIndex = 2))
        )

        val children = tree.children("${PodcastLibraryTree.PODCAST_PREFIX}$podcastId")

        assertThat(children.map { it.mediaId }).containsExactly("ep1", "ep2").inOrder()
    }

    @Test
    fun queue_children_resolve_to_real_playable_episodes_in_queue_order() = runTest {
        db.episodeDao().insertNew(listOf(episode(id = "ep1", chronoIndex = 1), episode(id = "ep2", chronoIndex = 2)))
        db.queueDao().insert(QueueEntity(episodeId = "ep2", position = 0, addedAt = 1L))
        db.queueDao().insert(QueueEntity(episodeId = "ep1", position = 1, addedAt = 2L))

        val children = tree.children(PodcastLibraryTree.QUEUE_ID)

        assertThat(children.map { it.mediaId }).containsExactly("ep2", "ep1").inOrder()
    }

    @Test
    fun unknown_parentId_returns_no_children() = runTest {
        assertThat(tree.children("something-unrecognized")).isEmpty()
    }

    @Test
    fun resolveForPlayback_resumes_at_the_saved_position_instead_of_restarting() = runTest {
        db.episodeDao().insertNew(listOf(episode(id = "ep1", chronoIndex = 1, positionMillis = 45_000L)))
        // What a browse tap actually sends: the MediaItem as returned by onGetChildren, with no
        // notion of the saved position (that's exactly the gap this method exists to close).
        val browsedItem = MediaItem.Builder().setMediaId("ep1").build()

        val resolved = tree.resolveForPlayback(listOf(browsedItem), startIndex = 0, startPositionMs = 0L)

        assertThat(resolved.startPositionMs).isEqualTo(45_000L)
        assertThat(resolved.mediaItems.single().localConfiguration?.uri).isNotNull()
    }

    @Test
    fun resolveForPlayback_starts_a_finished_episode_over_from_zero() = runTest {
        db.episodeDao().insertNew(
            listOf(episode(id = "ep1", chronoIndex = 1, positionMillis = 900_000L, isPlayed = true))
        )
        val browsedItem = MediaItem.Builder().setMediaId("ep1").build()

        val resolved = tree.resolveForPlayback(listOf(browsedItem), startIndex = 0, startPositionMs = 0L)

        assertThat(resolved.startPositionMs).isEqualTo(0L)
    }
}
