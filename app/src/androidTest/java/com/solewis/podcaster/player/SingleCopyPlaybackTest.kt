package com.solewis.podcaster.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.PlaceholderDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.testing.awaitPlayer
import com.solewis.podcaster.testing.onMain
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bug 1 end to end, through the app's own [PlayerFactory]: a real player, real HTTP, and a host that
 * hands every request after the first a differently stitched copy of the same length.
 *
 * What happened on the phone: a seek back cancelled the request playback was streaming from, the
 * reconnect got a copy whose audio sat 17,991,031 bytes further on, and the cache joined the two -
 * the clock kept running while the audio jumped back seven and a half minutes. Here the same moves
 * must leave exactly one request on the server and exactly one copy in the cache.
 */
@RunWith(AndroidJUnit4::class)
class SingleCopyPlaybackTest {

    private val server = MockWebServer()
    private val requests = AtomicInteger()
    private lateinit var downloadCache: SimpleCache
    private lateinit var streamCache: SimpleCache
    private lateinit var downloader: StreamDownloader
    private lateinit var player: ExoPlayer

    @Volatile private var throttled = false

    private val copyA = wav(Random(1))
    private val copyB = wav(Random(2))

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // One decision per request, as an ad-inserting host makes: the first gets A, the
                // rest get B.
                val body = if (requests.incrementAndGet() == 1) copyA else copyB
                val range = request.getHeader("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                    ?: return MockResponse().setResponseCode(200).setBody(okio.Buffer().write(body))
                val start = range.groupValues[1].toInt()
                val end = range.groupValues[2].toIntOrNull() ?: (body.size - 1)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${body.size}")
                    .setBody(okio.Buffer().write(body.copyOfRange(start, end + 1)))
            }
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "single-copy-${System.nanoTime()}")
        val database = StandaloneDatabaseProvider(context)
        downloadCache = SimpleCache(File(root, "downloads"), NoOpCacheEvictor(), database)
        streamCache = SimpleCache(File(root, "media"), NoOpCacheEvictor(), database)
        downloader = StreamDownloader(
            streamCache,
            DefaultHttpDataSource.Factory(),
            shouldThrottle = { throttled },
            // The smallest budget allowed, so the episodes here are several budgets long.
            aheadBudgetBytes = 2 * StreamDownloader.FRAGMENT_BYTES
        )
        onMain {
            player = PlayerFactory.create(context, downloadCache, streamCache, downloader)
            // Android 15 refuses audio focus to a process with no foreground activity or service,
            // which a bare instrumentation test is - so playback would sit ready and suppressed.
            // This test is about the data path, not focus, so it opts out.
            player.setAudioAttributes(player.audioAttributes, /* handleAudioFocus = */ false)
        }
    }

    @After
    fun tearDown() {
        onMain { player.release() }
        downloader.cancel()
        downloadCache.release()
        streamCache.release()
        server.shutdown()
    }

    @Test
    fun seeking_around_a_playing_episode_never_fetches_a_second_copy() {
        val item = MediaItem.Builder()
            .setMediaId(KEY)
            .setCustomCacheKey(KEY)
            .setUri(server.url("/episode.wav").toString())
            .build()
        onMain {
            player.setMediaItem(item)
            player.prepare()
            player.play()
        }
        awaitPlayer("playing") { onMain { player.isPlaying } }

        // Far forward, then back - the move that cancelled and reconnected before.
        onMain { player.seekTo(SECONDS * 1_000L - 5_000) }
        awaitPlayer("playing after the forward seek") { onMain { player.isPlaying && player.currentPosition > SECONDS * 1_000L - 5_000 } }
        onMain { player.seekTo(10_000) }
        awaitPlayer("playing after the seek back") {
            onMain { player.isPlaying && player.currentPosition in 10_000L..20_000L }
        }

        // The bug itself: before, the seek back reconnected and the host chose again.
        assertThat(requests.get()).isEqualTo(1)
        awaitPlayer("download finished") { downloader.stateOf(KEY) == StreamDownloader.State.Complete }
        // And the cache holds that one copy, whole - no join anywhere.
        assertThat(cached()).isEqualTo(copyA)
    }

    /**
     * What the phone does on cellular with "only on wifi": the download held to a little ahead of
     * playback. Reported as a spinner on every episode, and nothing playing again - first because
     * nothing the download fetched could be read until it had all arrived, and then because a skip
     * past the download left each waiting on the other. Driven through the moves that broke it:
     * start, skip to the end, then another episode resumed partway in.
     */
    @Test
    fun a_throttled_download_plays_from_the_start_after_a_skip_to_the_end_and_from_a_resume_point() {
        throttled = true
        val first = MediaItem.Builder().setMediaId(KEY).setCustomCacheKey(KEY)
            .setUri(server.url("/episode.wav").toString()).build()
        onMain {
            player.setMediaItem(first)
            player.prepare()
            player.play()
        }
        awaitPlayer("playing from the start") { onMain { player.isPlaying && player.currentPosition > 500 } }

        onMain { player.seekTo(SECONDS * 1_000L - 5_000) }
        awaitPlayer("playing after the skip to the end") {
            onMain { player.isPlaying && player.currentPosition > SECONDS * 1_000L - 5_000 }
        }

        val second = MediaItem.Builder().setMediaId("ep-second").setCustomCacheKey("ep-second")
            .setUri(server.url("/second.wav").toString()).build()
        onMain {
            player.setMediaItem(second, /* startPositionMs = */ SECONDS * 1_000L / 2)
            player.prepare()
            player.play()
        }
        awaitPlayer("playing the next episode from where it was left") {
            onMain { player.isPlaying && player.currentPosition > SECONDS * 1_000L / 2 }
        }
    }

    private fun cached(): ByteArray {
        val length = streamCache.getCachedBytes(KEY, 0, C.LENGTH_UNSET.toLong()).toInt()
        val source = CacheDataSource(streamCache, PlaceholderDataSource.INSTANCE)
        source.open(DataSpec.Builder().setUri(Uri.EMPTY).setKey(KEY).setLength(length.toLong()).build())
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) read += source.read(bytes, read, length - read)
        source.close()
        return bytes
    }

    /** A playable WAV whose samples - its "audio" - are particular to [random]. */
    private fun wav(random: Random): ByteArray {
        val sampleRate = 8_000
        val data = random.nextBytes(SECONDS * sampleRate * 2)
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }
        return header.array() + data
    }

    private companion object {
        const val KEY = "ep-single-copy"
        const val SECONDS = 600
    }
}
