package com.solewis.podcaster.data.repo

import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.remote.FeedFetcher
import com.solewis.podcaster.testing.FeedHost
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The preview a show gets before you subscribe. Its ordering has to agree with what
 * [SubscriptionRepository] will choose on subscribe, otherwise the list visibly reshuffles the
 * moment you commit.
 */
class ShowPreviewRepositoryTest {

    private lateinit var host: FeedHost
    private lateinit var repository: ShowPreviewRepository

    @Before
    fun setUp() {
        host = FeedHost()
        repository = ShowPreviewRepository(FeedFetcher())
    }

    @After
    fun tearDown() = host.close()

    @Test
    fun a_serial_previews_oldest_first_matching_what_subscribing_will_choose() = runTest {
        host.enqueueFeed("serial_with_episode_numbers.xml")

        val preview = repository.load(host.feedUrl(), seedTitle = null)

        assertThat(preview?.title).isEqualTo("A Serial Audio Drama")
        assertThat(preview?.episodes?.first()?.title).isEqualTo("Chapter 1")
    }

    @Test
    fun an_ordinary_show_previews_newest_first() = runTest {
        host.enqueueFeed("nyt_daily_slice.xml")

        val preview = repository.load(host.feedUrl(), seedTitle = null)

        val chronoIndices = preview!!.episodes.mapNotNull { it.chronoIndex }
        assertThat(chronoIndices).isInOrder(Comparator<Int> { a, b -> b.compareTo(a) })
    }

    @Test
    fun a_dead_feed_returns_null_instead_of_throwing_into_the_ui() = runTest {
        host.enqueueStatus(404)

        assertThat(repository.load(host.feedUrl(), seedTitle = "Whatever")).isNull()
    }

    @Test
    fun a_malformed_feed_returns_null() = runTest {
        host.enqueueFeed("malformed.xml")

        assertThat(repository.load(host.feedUrl(), seedTitle = null)).isNull()
    }

    @Test
    fun the_search_title_is_used_when_the_feed_omits_one() = runTest {
        host.enqueueBody("""<?xml version="1.0"?><rss version="2.0"><channel></channel></rss>""", "application/xml")

        assertThat(repository.load(host.feedUrl(), seedTitle = "From Search")?.title).isEqualTo("From Search")
    }
}
