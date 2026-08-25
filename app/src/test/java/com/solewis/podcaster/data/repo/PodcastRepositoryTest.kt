package com.solewis.podcaster.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.db.PodcasterDatabase
import com.solewis.podcaster.data.db.model.SortOrder
import com.solewis.podcaster.testing.episodeRow
import com.solewis.podcaster.testing.inMemoryDatabase
import com.solewis.podcaster.testing.podcastRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PodcastRepositoryTest {

    private lateinit var db: PodcasterDatabase
    private lateinit var repository: PodcastRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        repository = PodcastRepository(db.podcastDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun subscribed_feed_urls_are_exposed_as_a_lookup_for_the_search_screen() = runTest {
        val id = db.podcastDao().insert(podcastRow(title = "Show A", feedUrl = "https://a.example/f.xml"))
        db.podcastDao().insert(podcastRow(title = "Show B", feedUrl = "https://b.example/f.xml"))

        val map = repository.observeSubscribedFeedUrls().first()

        // Search only knows a show by its feed URL, so this is what decides whether a result
        // renders as "Subscribe" or "Subscribed".
        assertThat(map).containsEntry("https://a.example/f.xml", id)
        assertThat(map).hasSize(2)
    }

    @Test
    fun the_sort_toggle_persists() = runTest {
        val id = db.podcastDao().insert(podcastRow())

        repository.setSortOrder(id, SortOrder.OLDEST_FIRST)

        assertThat(repository.observeById(id).first()?.sortOrder).isEqualTo(SortOrder.OLDEST_FIRST)
    }

    @Test
    fun unsubscribing_takes_the_shows_episodes_and_history_with_it() = runTest {
        val id = db.podcastDao().insert(podcastRow())
        db.episodeDao().insertNew(listOf(episodeRow(id, "1", positionMillis = 5_000)))

        repository.unsubscribe(id)

        assertThat(repository.observeAll().first()).isEmpty()
        // Documented as having no undo - worth pinning so the cascade isn't quietly lost.
        assertThat(db.episodeDao().getAllForPodcast(id)).isEmpty()
    }

    @Test
    fun shows_are_listed_newest_subscription_first() = runTest {
        db.podcastDao().insert(podcastRow(title = "Older", subscribedAt = 1_000))
        db.podcastDao().insert(podcastRow(title = "Newer", subscribedAt = 9_000))

        assertThat(repository.observeAll().first().map { it.title })
            .containsExactly("Newer", "Older").inOrder()
    }
}
