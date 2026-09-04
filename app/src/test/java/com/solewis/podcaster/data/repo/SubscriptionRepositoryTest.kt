package com.solewis.podcaster.data.repo

import com.google.common.truth.Truth.assertThat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.solewis.podcaster.data.db.PodcasterDatabase
import com.solewis.podcaster.data.db.model.SortOrder
import com.solewis.podcaster.data.remote.FeedFetcher
import com.solewis.podcaster.testing.FeedHost
import com.solewis.podcaster.testing.inMemoryDatabase
import com.solewis.podcaster.testing.podcastRow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the orchestration [SubscriptionRepository] layers on top of the DAOs: real HTTP (against a
 * local [FeedHost]), real parsing, real mapping, real writes.
 *
 * Deliberately not a re-test of `EpisodeDaoTest`, which already pins the SQL-level contracts. The
 * risk here is a different one - that some future change swaps `insertNew` + `updateMetadata` for an
 * upsert, or drops a column from the hand-written 19-column update, and the DAO tests keep passing
 * because the DAO didn't change. These tests exercise the path the app actually takes.
 */
@RunWith(AndroidJUnit4::class)
class SubscriptionRepositoryTest {

    private lateinit var db: PodcasterDatabase
    private lateinit var host: FeedHost
    private lateinit var repository: SubscriptionRepository
    private var clock = 1_000L

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        host = FeedHost()
        repository = SubscriptionRepository(
            podcastDao = db.podcastDao(),
            episodeDao = db.episodeDao(),
            feedFetcher = FeedFetcher(),
            now = { clock }
        )
    }

    @After
    fun tearDown() {
        host.close()
        db.close()
    }

    private suspend fun subscribeToHost(): Long {
        host.enqueueFeed("rotating_token_v1.xml")
        return (repository.subscribe(host.feedUrl()) as SubscribeResult.Success).podcastId
    }

    @Test
    fun a_show_checked_moments_ago_is_left_alone_by_the_automatic_refresh() = runTest {
        subscribeToHost()

        repository.refreshStale()

        // Foregrounding happens on every task switch and rotation. Without the gate this is a feed
        // request each time, which is both rude to the host and pointless.
        assertThat(host.requestCount).isEqualTo(1)
    }

    @Test
    fun a_show_not_checked_in_a_while_is_refreshed_automatically() = runTest {
        subscribeToHost()
        clock += SubscriptionRepository.STALE_AFTER_MILLIS + 1
        host.enqueueNotModified()

        repository.refreshStale()

        assertThat(host.requestCount).isEqualTo(2)
    }

    @Test
    fun a_show_never_checked_at_all_counts_as_stale() = runTest {
        // The state a row can be left in by a migration, or by a subscribe that predates the
        // column: null must not read as "checked at time zero, so recently enough".
        db.podcastDao().insert(podcastRow(feedUrl = host.feedUrl(), lastRefreshedAt = null))
        host.enqueueNotModified()

        repository.refreshStale()

        assertThat(host.requestCount).isEqualTo(1)
    }

    @Test
    fun refreshing_one_stale_show_reports_what_happened() = runTest {
        val id = subscribeToHost()
        clock += SubscriptionRepository.STALE_AFTER_MILLIS + 1
        host.enqueueNotModified()

        assertThat(repository.refreshIfStale(id)).isEqualTo(RefreshResult.NotModified)
    }

    @Test
    fun refreshing_one_fresh_show_reports_that_it_skipped() = runTest {
        val id = subscribeToHost()

        // Null rather than a result, so a caller showing a spinner knows to leave it down.
        assertThat(repository.refreshIfStale(id)).isNull()
        assertThat(host.requestCount).isEqualTo(1)
    }

    @Test
    fun an_explicit_refresh_always_asks_however_recently_it_was_checked() = runTest {
        subscribeToHost()
        host.enqueueNotModified()

        // Pull-to-refresh, the button on a show, and the periodic worker all mean "ask now" - the
        // staleness gate belongs only to the automatic paths.
        repository.refreshAll()

        assertThat(host.requestCount).isEqualTo(2)
    }

    // ---- subscribe ----

    @Test
    fun subscribe_imports_the_show_and_all_of_its_episodes() = runTest {
        host.enqueueFeed("serial_with_episode_numbers.xml")

        val result = repository.subscribe(host.feedUrl())

        assertThat(result).isInstanceOf(SubscribeResult.Success::class.java)
        val podcastId = (result as SubscribeResult.Success).podcastId

        val podcast = db.podcastDao().getById(podcastId)
        assertThat(podcast?.title).isEqualTo("A Serial Audio Drama")
        assertThat(podcast?.subscribedAt).isEqualTo(clock)
        assertThat(db.episodeDao().getAllForPodcast(podcastId)).isNotEmpty()
    }

    @Test
    fun subscribe_derives_oldest_first_ordering_for_a_serial_show() = runTest {
        host.enqueueFeed("serial_with_episode_numbers.xml")

        val podcastId = (repository.subscribe(host.feedUrl()) as SubscribeResult.Success).podcastId

        // A serial is meant to be started at chapter one, so the show screen must not default to
        // the newest-first ordering every other kind of feed wants.
        assertThat(db.podcastDao().getById(podcastId)?.sortOrder).isEqualTo(SortOrder.OLDEST_FIRST)
    }

    @Test
    fun subscribe_defaults_to_newest_first_for_an_ordinary_show() = runTest {
        host.enqueueFeed("nyt_daily_slice.xml")

        val podcastId = (repository.subscribe(host.feedUrl()) as SubscribeResult.Success).podcastId

        assertThat(db.podcastDao().getById(podcastId)?.sortOrder).isEqualTo(SortOrder.NEWEST_FIRST)
    }

    @Test
    fun subscribe_stores_the_caching_headers_so_the_next_refresh_can_be_conditional() = runTest {
        host.enqueueFeed("rotating_token_v1.xml", etag = "\"abc123\"", lastModified = "Wed, 01 Jan 2025 00:00:00 GMT")

        val podcastId = (repository.subscribe(host.feedUrl()) as SubscribeResult.Success).podcastId

        val podcast = db.podcastDao().getById(podcastId)
        assertThat(podcast?.httpEtag).isEqualTo("\"abc123\"")
        assertThat(podcast?.httpLastModified).isEqualTo("Wed, 01 Jan 2025 00:00:00 GMT")
    }

    @Test
    fun subscribing_twice_to_the_same_feed_does_not_duplicate_anything() = runTest {
        host.enqueueFeed("serial_with_episode_numbers.xml")
        val first = repository.subscribe(host.feedUrl()) as SubscribeResult.Success

        val second = repository.subscribe(host.feedUrl())

        assertThat(second).isEqualTo(SubscribeResult.AlreadySubscribed(first.podcastId))
        assertThat(db.podcastDao().getAllIds()).hasSize(1)
        // The short-circuit happens before any network call, so only the first subscribe hit the host.
        assertThat(host.requestCount).isEqualTo(1)
    }

    @Test
    fun subscribe_writes_nothing_when_the_host_returns_an_error() = runTest {
        host.enqueueStatus(500)

        val result = repository.subscribe(host.feedUrl())

        assertThat(result).isInstanceOf(SubscribeResult.Failure::class.java)
        assertThat(db.podcastDao().getAllIds()).isEmpty()
    }

    @Test
    fun subscribe_fails_cleanly_on_a_soft_404_that_returns_200_with_an_error_document() = runTest {
        // A real pattern: the host answers 200 with an XML error body instead of a feed. Parsing
        // has to reject it rather than creating a subscription with no episodes.
        host.enqueueFeed("soft_404_error_page.xml")

        val result = repository.subscribe(host.feedUrl())

        assertThat(result).isInstanceOf(SubscribeResult.Failure::class.java)
        assertThat(db.podcastDao().getAllIds()).isEmpty()
    }

    @Test
    fun subscribe_falls_back_to_the_search_result_title_when_the_feed_has_none() = runTest {
        host.enqueueBody("""<?xml version="1.0"?><rss version="2.0"><channel><title></title></channel></rss>""", "application/xml")

        val result = repository.subscribe(host.feedUrl(), seedTitle = "From Search")

        val podcastId = (result as SubscribeResult.Success).podcastId
        assertThat(db.podcastDao().getById(podcastId)?.title).isEqualTo("From Search")
    }

    // ---- refresh ----

    @Test
    fun refresh_sends_the_stored_validators_and_treats_304_as_no_change() = runTest {
        host.enqueueFeed("rotating_token_v1.xml", etag = "\"v1\"")
        val podcastId = (repository.subscribe(host.feedUrl()) as SubscribeResult.Success).podcastId
        host.takeRequest()
        host.enqueueNotModified()
        clock = 5_000L

        val result = repository.refresh(podcastId)

        assertThat(result).isEqualTo(RefreshResult.NotModified)
        assertThat(host.takeRequest().getHeader("If-None-Match")).isEqualTo("\"v1\"")
        val podcast = db.podcastDao().getById(podcastId)
        // A 304 is still a successful check-in: the timestamp advances and the validator is kept,
        // otherwise every refresh would re-download a feed the host just told us hasn't changed.
        assertThat(podcast?.lastRefreshedAt).isEqualTo(5_000L)
        assertThat(podcast?.httpEtag).isEqualTo("\"v1\"")
    }

    @Test
    fun refresh_reports_how_many_episodes_are_genuinely_new() = runTest {
        host.enqueueFeed("serial_with_episode_numbers.xml")
        val podcastId = (repository.subscribe(host.feedUrl()) as SubscribeResult.Success).podcastId
        val originalCount = db.episodeDao().getAllForPodcast(podcastId).size
        host.enqueueFeed("serial_with_episode_numbers.xml")

        val result = repository.refresh(podcastId)

        assertThat(result).isEqualTo(RefreshResult.Success(episodesAdded = 0))
        assertThat(db.episodeDao().getAllForPodcast(podcastId)).hasSize(originalCount)
    }

    @Test
    fun refresh_preserves_playback_progress_through_the_whole_fetch_and_update_path() = runTest {
        host.enqueueFeed("serial_with_episode_numbers.xml")
        val podcastId = (repository.subscribe(host.feedUrl()) as SubscribeResult.Success).podcastId
        val episode = db.episodeDao().getAllForPodcast(podcastId).first()
        db.episodeDao().setProgress(episode.id, positionMillis = 90_000, isPlayed = false, now = 2_000L)
        host.enqueueFeed("serial_with_episode_numbers.xml")

        repository.refresh(podcastId)

        // The entire reason this app exists. If a refresh can reset this, nothing else matters.
        val after = db.episodeDao().getById(episode.id)
        assertThat(after?.positionMillis).isEqualTo(90_000)
        assertThat(after?.lastPlayedAt).isEqualTo(2_000L)
    }

    @Test
    fun refresh_updates_a_rotating_enclosure_url_without_disturbing_progress() = runTest {
        // Real hosts rotate a tracking token into the enclosure URL. Episode identity is derived
        // from the *normalized* URL, so the row must be recognised as the same episode - updated
        // in place, not re-inserted as a duplicate and not reset.
        host.enqueueFeed("rotating_token_v1.xml")
        val podcastId = (repository.subscribe(host.feedUrl()) as SubscribeResult.Success).podcastId
        val episode = db.episodeDao().getAllForPodcast(podcastId).single()
        db.episodeDao().setProgress(episode.id, positionMillis = 45_000, isPlayed = false, now = 2_000L)
        host.enqueueFeed("rotating_token_v2.xml")

        val result = repository.refresh(podcastId)

        assertThat(result).isEqualTo(RefreshResult.Success(episodesAdded = 0))
        val episodes = db.episodeDao().getAllForPodcast(podcastId)
        assertThat(episodes).hasSize(1)
        assertThat(episodes.single().enclosureUrl).contains("updated=1700099999")
        assertThat(episodes.single().positionMillis).isEqualTo(45_000)
    }

    @Test
    fun refresh_applies_changed_metadata_to_an_existing_episode() = runTest {
        host.enqueueFeed("rotating_token_v1.xml")
        val podcastId = (repository.subscribe(host.feedUrl()) as SubscribeResult.Success).podcastId
        val episodeId = db.episodeDao().getAllForPodcast(podcastId).single().id
        host.enqueueBody(
            Fixture.renamedRotatingTokenFeed(newTitle = "Episode One (Remastered)"),
            "application/xml"
        )

        repository.refresh(podcastId)

        assertThat(db.episodeDao().getById(episodeId)?.title).isEqualTo("Episode One (Remastered)")
    }

    @Test
    fun refresh_drops_a_vanished_episode_that_was_never_played() = runTest {
        host.enqueueFeed("rotating_token_v1.xml")
        val podcastId = (repository.subscribe(host.feedUrl()) as SubscribeResult.Success).podcastId
        assertThat(db.episodeDao().getAllForPodcast(podcastId)).hasSize(1)
        host.enqueueBody(Fixture.emptyFeed(), "application/xml")

        repository.refresh(podcastId)

        assertThat(db.episodeDao().getAllForPodcast(podcastId)).isEmpty()
    }

    @Test
    fun refresh_keeps_a_vanished_episode_that_has_listening_history() = runTest {
        host.enqueueFeed("rotating_token_v1.xml")
        val podcastId = (repository.subscribe(host.feedUrl()) as SubscribeResult.Success).podcastId
        val episode = db.episodeDao().getAllForPodcast(podcastId).single()
        db.episodeDao().setProgress(episode.id, positionMillis = 10_000, isPlayed = false, now = 2_000L)
        host.enqueueBody(Fixture.emptyFeed(), "application/xml")

        repository.refresh(podcastId)

        // Feeds routinely drop old items behind a paywall or a window. Something you've actually
        // listened to should not silently disappear from your history because of that.
        assertThat(db.episodeDao().getAllForPodcast(podcastId)).hasSize(1)
    }

    @Test
    fun refresh_records_the_failure_on_the_show_rather_than_throwing() = runTest {
        host.enqueueFeed("rotating_token_v1.xml", etag = "\"keep-me\"")
        val podcastId = (repository.subscribe(host.feedUrl()) as SubscribeResult.Success).podcastId
        host.enqueueStatus(503)
        clock = 9_000L

        val result = repository.refresh(podcastId)

        assertThat(result).isInstanceOf(RefreshResult.Failure::class.java)
        val podcast = db.podcastDao().getById(podcastId)
        assertThat(podcast?.lastRefreshFailedAt).isEqualTo(9_000L)
        assertThat(podcast?.lastRefreshError).contains("503")
        // A failed refresh must not advance the success timestamp or discard the validator - losing
        // the ETag would turn every subsequent refresh back into a full re-download.
        assertThat(podcast?.httpEtag).isEqualTo("\"keep-me\"")
        assertThat(podcast?.lastRefreshedAt).isEqualTo(1_000L)
    }

    @Test
    fun refresh_of_a_deleted_show_fails_instead_of_creating_one() = runTest {
        val result = repository.refresh(podcastId = 999)

        assertThat(result).isInstanceOf(RefreshResult.Failure::class.java)
        assertThat(host.requestCount).isEqualTo(0)
    }

    @Test
    fun refreshAll_visits_every_subscription() = runTest {
        host.enqueueFeed("rotating_token_v1.xml")
        repository.subscribe(host.feedUrl("/one.xml"))
        host.enqueueFeed("serial_with_episode_numbers.xml")
        repository.subscribe(host.feedUrl("/two.xml"))
        host.enqueueNotModified()
        host.enqueueNotModified()

        val results = repository.refreshAll()

        assertThat(results).hasSize(2)
        assertThat(results).containsExactly(RefreshResult.NotModified, RefreshResult.NotModified)
    }

    /** Small hand-written feeds for cases no captured fixture covers. */
    private object Fixture {
        fun renamedRotatingTokenFeed(newTitle: String) = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>Rotating Token Show</title>
                <item>
                  <title>$newTitle</title>
                  <pubDate>Mon, 01 Jan 2024 08:00:00 +0000</pubDate>
                  <itunes:duration>15:00</itunes:duration>
                  <enclosure url="https://dts.podtrac.com/redirect.mp3/cdn.example.com/ep1.mp3?updated=1700000001" length="100" type="audio/mpeg"/>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        fun emptyFeed() = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Rotating Token Show</title></channel></rss>
        """.trimIndent()
    }

    // ---- no connection ----
    //
    // A host in the reserved .invalid TLD, which by RFC 2606 can never resolve, so this produces a
    // real UnknownHostException through the real OkHttp stack rather than a stubbed one. That is
    // the exception that actually crashed the app on a phone with no signal.
    private fun unresolvableFeedUrl() = "http://podcaster-does-not-exist.invalid/feed.xml"

    @Test
    fun refreshing_with_no_connection_reports_a_failure_instead_of_throwing() = runTest {
        val podcastId = db.podcastDao().insert(
            podcastRow(feedUrl = unresolvableFeedUrl(), lastRefreshedAt = null)
        )

        val result = repository.refresh(podcastId)

        // The crash was an UnknownHostException escaping this call. Reaching the assertion at all
        // is most of what this test checks.
        assertThat(result).isInstanceOf(RefreshResult.Failure::class.java)
        assertThat((result as RefreshResult.Failure).message).isEqualTo("No connection")
    }

    @Test
    fun the_automatic_refresh_with_no_connection_does_not_throw() = runTest {
        // The exact path that killed the process: refreshStale runs from a Compose scope on every
        // foreground, and an exception escaping it is a dead app rather than a failed refresh.
        db.podcastDao().insert(podcastRow(feedUrl = unresolvableFeedUrl(), lastRefreshedAt = null))

        val results = repository.refreshStale()

        assertThat(results).hasSize(1)
        assertThat(results.single()).isInstanceOf(RefreshResult.Failure::class.java)
    }

    @Test
    fun one_unreachable_feed_does_not_stop_the_others_refreshing() = runTest {
        // `coroutineScope` cancels its siblings when a child throws, so one dead feed used to
        // abort the whole batch - the reason the crash report listed three different hosts as
        // suppressed exceptions on one throw.
        host.enqueueFeed("lex_fridman.xml")
        db.podcastDao().insert(podcastRow(feedUrl = unresolvableFeedUrl(), lastRefreshedAt = null))
        db.podcastDao().insert(podcastRow(feedUrl = host.feedUrl(), lastRefreshedAt = null))

        val results = repository.refreshStale()

        assertThat(results).hasSize(2)
        assertThat(results.filterIsInstance<RefreshResult.Failure>()).hasSize(1)
        assertThat(results.filterIsInstance<RefreshResult.Success>()).hasSize(1)
    }

    @Test
    fun subscribing_with_no_connection_reports_a_failure_instead_of_throwing() = runTest {
        val result = repository.subscribe(unresolvableFeedUrl())

        assertThat(result).isInstanceOf(SubscribeResult.Failure::class.java)
        assertThat((result as SubscribeResult.Failure).message).isEqualTo("No connection")
    }

    @Test
    fun a_failed_refresh_is_recorded_against_the_show_so_it_is_retried_rather_than_marked_fresh() =
        runTest {
            val podcastId = db.podcastDao().insert(
                podcastRow(feedUrl = unresolvableFeedUrl(), lastRefreshedAt = null)
            )

            repository.refresh(podcastId)

            // lastRefreshedAt must stay unset: a failure that counted as a check would leave the
            // show fresh for fifteen minutes and skip the retry once the connection came back.
            assertThat(db.podcastDao().getById(podcastId)?.lastRefreshedAt).isNull()
        }

    @Test
    fun a_refresh_that_fails_for_a_reason_that_is_not_the_network_still_reports_rather_than_throws() =
        runTest {
            // Not a bad connection - a bug. An OkHttp interceptor throwing a RuntimeException comes
            // straight out of execute(), past every IOException catch, exactly as the
            // UnknownHostException did. The automatic refresh runs from a Compose scope, so
            // anything that escapes it kills the app rather than failing a refresh; this pins that
            // the batch reports it instead.
            val exploding = OkHttpClient.Builder()
                .addInterceptor { error("the parser exploded") }
                .build()
            val repositoryWithBug = SubscriptionRepository(
                podcastDao = db.podcastDao(),
                episodeDao = db.episodeDao(),
                feedFetcher = FeedFetcher(exploding),
                now = { clock }
            )
            db.podcastDao().insert(podcastRow(feedUrl = host.feedUrl(), lastRefreshedAt = null))

            val results = repositoryWithBug.refreshStale()

            assertThat(results).hasSize(1)
            assertThat(results.single()).isInstanceOf(RefreshResult.Failure::class.java)
        }
}
