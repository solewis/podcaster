package com.solewis.podcaster.data.repo

import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.remote.ItunesRateLimitedException
import com.solewis.podcaster.data.remote.ItunesSearchApi
import com.solewis.podcaster.testing.FeedHost
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Search over real HTTP against a local stand-in for the iTunes Search API. No Robolectric needed -
 * nothing here touches Room or any Android framework class.
 */
class SearchRepositoryTest {

    private lateinit var host: FeedHost
    private lateinit var repository: SearchRepository

    @Before
    fun setUp() {
        host = FeedHost()
        repository = SearchRepository(ItunesSearchApi(httpClient = host.hostRedirectingClient()))
    }

    @After
    fun tearDown() = host.close()

    @Test
    fun a_result_is_mapped_to_what_the_search_screen_needs() = runTest {
        host.enqueueBody(
            """
            {"resultCount":1,"results":[{
              "collectionId":1234,"collectionName":"Acquired","artistName":"Ben and David",
              "feedUrl":"https://feeds.example.com/acquired","artworkUrl600":"https://img/600.jpg",
              "artworkUrl100":"https://img/100.jpg","trackCount":180
            }]}
            """.trimIndent()
        )

        val results = repository.search("acquired")

        assertThat(results).hasSize(1)
        with(results.single()) {
            assertThat(itunesCollectionId).isEqualTo(1234)
            assertThat(title).isEqualTo("Acquired")
            assertThat(author).isEqualTo("Ben and David")
            assertThat(feedUrl).isEqualTo("https://feeds.example.com/acquired")
            // The 600px art is what the show banner needs; 100px is only a fallback.
            assertThat(artworkUrl).isEqualTo("https://img/600.jpg")
            assertThat(episodeCountHint).isEqualTo(180)
        }
    }

    @Test
    fun artwork_falls_back_to_the_small_image_when_there_is_no_large_one() = runTest {
        host.enqueueBody(
            """{"results":[{"collectionName":"X","feedUrl":"https://f","artworkUrl100":"https://img/100.jpg"}]}"""
        )

        assertThat(repository.search("x").single().artworkUrl).isEqualTo("https://img/100.jpg")
    }

    @Test
    fun a_result_with_no_feed_url_is_dropped_because_it_cannot_be_subscribed_to() = runTest {
        host.enqueueBody(
            """
            {"results":[
              {"collectionName":"No Feed"},
              {"collectionName":"Has Feed","feedUrl":"https://f"}
            ]}
            """.trimIndent()
        )

        assertThat(repository.search("x").map { it.title }).containsExactly("Has Feed")
    }

    @Test
    fun an_untitled_show_still_gets_a_label_rather_than_rendering_blank() = runTest {
        host.enqueueBody("""{"results":[{"feedUrl":"https://f"}]}""")

        assertThat(repository.search("x").single().title).isEqualTo("(untitled show)")
    }

    @Test
    fun unknown_fields_in_the_response_are_ignored() = runTest {
        // The real API returns dozens of fields we don't model, and adds more over time.
        host.enqueueBody(
            """{"results":[{"collectionName":"X","feedUrl":"https://f","somethingNew":{"nested":true}}]}"""
        )

        assertThat(repository.search("x")).hasSize(1)
    }

    @Test
    fun a_blank_term_short_circuits_without_calling_the_api() = runTest {
        assertThat(repository.search("   ")).isEmpty()
        assertThat(host.requestCount).isEqualTo(0)
    }

    @Test
    fun throttling_surfaces_as_its_own_exception_so_the_ui_can_explain_it() = runTest {
        host.enqueueStatus(429)

        val thrown = runCatching { repository.search("x") }.exceptionOrNull()

        // A distinct type rather than a generic HTTP error, so the UI can say "try again shortly"
        // instead of "something went wrong".
        assertThat(thrown).isInstanceOf(ItunesRateLimitedException::class.java)
    }

    @Test
    fun the_request_carries_the_podcast_filters_the_api_needs() = runTest {
        host.enqueueBody("""{"results":[]}""")

        repository.search("acquired")

        val url = host.takeRequest().requestUrl!!
        assertThat(url.queryParameter("term")).isEqualTo("acquired")
        assertThat(url.queryParameter("media")).isEqualTo("podcast")
        assertThat(url.queryParameter("entity")).isEqualTo("podcast")
    }
}
