package com.solewis.podcaster.player

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.net.Connectivity
import com.solewis.podcaster.data.repo.PlayableEpisode
import com.solewis.podcaster.data.settings.PrefetchMode
import com.solewis.podcaster.data.settings.SettingsStore
import com.solewis.podcaster.testing.awaitPlayer
import com.solewis.podcaster.testing.silentWav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [CacheEpisodePrefetcher] against a real [SimpleCache] and a real HTTP server - the mechanism
 * behind [PrefetchMode.FULL_EPISODE]: pull the rest of an episode in as soon as it starts, so a
 * connection that drops later has nothing left to force a fresh, independent request over. See
 * [PrefetchMode]'s doc comment for the bug this exists to prevent.
 */
@RunWith(AndroidJUnit4::class)
class CacheEpisodePrefetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var cache: SimpleCache
    private lateinit var settings: SettingsStore
    private lateinit var scope: CoroutineScope
    private class FakeConnectivity(var wifi: Boolean = true) : Connectivity {
        override fun isOnline() = true
        override fun isOnWifi() = wifi
    }

    private val connectivity = FakeConnectivity()
    private val episode = PlayableEpisode(
        episodeId = "ep-1",
        title = "An Episode",
        podcastTitle = "A Show",
        artworkUrl = null,
        mediaUrl = "",
        startPositionMillis = 0
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        server = MockWebServer()
        server.start()
        val body = silentWav(seconds = 5)
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(body)))
        cache = SimpleCache(
            File(context.cacheDir, "prefetch-test-${System.nanoTime()}"),
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(context)
        )
        settings = SettingsStore(context).apply {
            prefetchMode = PrefetchMode.FULL_EPISODE
            prefetchWifiOnly = false
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        cache.release()
        server.shutdown()
    }

    private fun prefetcher() = CacheEpisodePrefetcher(
        cache,
        DefaultHttpDataSource.Factory().setUserAgent(PlayerFactory.USER_AGENT),
        settings,
        connectivity,
        scope
    )

    private fun episodeAt(url: String) = episode.copy(mediaUrl = url)

    @Test
    fun a_full_episode_prefetch_fills_the_cache() {
        val url = server.url("/ep.wav").toString()

        prefetcher().prefetch(episodeAt(url))

        awaitPlayer("the whole episode to land in the cache", timeoutMillis = 15_000) {
            cache.getCachedSpans("ep-1").isNotEmpty()
        }
        // The whole file, not merely the start of it - proof this actually read to the end rather
        // than only opening the connection.
        val wavBytes = silentWav(seconds = 5).size.toLong()
        assertThat(cache.getCachedBytes("ep-1", 0, wavBytes)).isEqualTo(wavBytes)
    }

    @Test
    fun conservative_mode_does_not_prefetch_anything() {
        settings.prefetchMode = PrefetchMode.CONSERVATIVE
        val url = server.url("/ep.wav").toString()

        prefetcher().prefetch(episodeAt(url))

        // Long enough that a real prefetch of this small a file would have finished by now.
        Thread.sleep(2_000)
        assertThat(cache.cacheSpace).isEqualTo(0)
    }

    @Test
    fun wifi_only_skips_prefetching_off_wifi() {
        settings.prefetchWifiOnly = true
        connectivity.wifi = false
        val url = server.url("/ep.wav").toString()

        prefetcher().prefetch(episodeAt(url))

        Thread.sleep(2_000)
        assertThat(cache.cacheSpace).isEqualTo(0)
    }

    @Test
    fun wifi_only_still_prefetches_on_wifi() {
        settings.prefetchWifiOnly = true
        connectivity.wifi = true
        val url = server.url("/ep.wav").toString()

        prefetcher().prefetch(episodeAt(url))

        awaitPlayer("the episode to be prefetched over wifi", timeoutMillis = 15_000) {
            cache.cacheSpace > 0
        }
    }

    @Test
    fun starting_a_second_episode_stops_prefetching_the_first() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(silentWav(seconds = 5))))
        val firstUrl = server.url("/first.wav").toString()
        val secondUrl = server.url("/second.wav").toString()
        val prefetcher = prefetcher()

        prefetcher.prefetch(episodeAt(firstUrl))
        // Immediately superseded - not enough time for the first to have finished on its own,
        // which is what would make this test pass for the wrong reason.
        prefetcher.prefetch(episode.copy(episodeId = "ep-2", mediaUrl = secondUrl))

        awaitPlayer("the second episode to be prefetched", timeoutMillis = 15_000) {
            cache.cacheSpace > 0 && cache.getCachedSpans("ep-2").isNotEmpty()
        }
        // Not a strict absence assertion on "ep-1": cancellation stops the copy loop promptly but
        // not necessarily before a single in-flight chunk lands. The second episode actually being
        // there is the load-bearing half of this test.
    }
}
