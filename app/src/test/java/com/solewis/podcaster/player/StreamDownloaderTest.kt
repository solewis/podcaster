package com.solewis.podcaster.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.PlaceholderDataSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * One listen, one copy of the file - against a host that behaves the way the ones behind the
 * reported jumps do: every request is a separate decision, and the next one can hand back a copy
 * with the same audio at different byte offsets.
 *
 * A real SimpleCache on disk, because the cache's own rules - spans only visible once committed,
 * locks on uncached ranges - are exactly what this has to get right.
 */
@RunWith(AndroidJUnit4::class)
class StreamDownloaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var cache: SimpleCache
    private val host = FakeHost()
    private val log = PlaybackLog(File.createTempFile("downloader-log", ".txt"))
    private var throttled = false
    private lateinit var downloader: StreamDownloader

    private val copyA = Random(1).nextBytes(3 * 1024 * 1024)

    /** The same audio as [copyA] behind 200KB of different "ads": same length, shifted content. */
    private val copyB = Random(2).nextBytes(200 * 1024) + copyA.copyOfRange(0, copyA.size - 200 * 1024)

    @Before
    fun setUp() {
        cache = SimpleCache(
            folder.newFolder("media"),
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(ApplicationProvider.getApplicationContext())
        )
        downloader = newDownloader()
    }

    @After
    fun tearDown() {
        downloader.cancel()
        cache.release()
    }

    private fun newDownloader(aheadBudget: Long = StreamDownloader.THROTTLED_AHEAD_BYTES) = StreamDownloader(
        cache = cache,
        upstreamFactory = host,
        shouldThrottle = { throttled },
        log = log,
        retryDelaysMillis = listOf(20L),
        aheadBudgetBytes = aheadBudget
    )

    @Test
    fun a_whole_episode_comes_from_a_single_request() {
        host.serve(copyA)

        downloader.ensure(KEY, URI)

        awaitState(StreamDownloader.State.Complete)
        assertThat(host.requests.get()).isEqualTo(1)
        assertThat(cachedBytes()).isEqualTo(copyA)
    }

    /**
     * The phone's bug 1: a seek cancelled the streaming request, the next request got a copy shifted
     * by 7.5 minutes, and the cache joined them. Playback reading - at any position, after any seek -
     * must not be what reaches the network.
     */
    @Test
    fun playback_reads_and_seeks_never_make_a_request_of_their_own() {
        host.serve(copyA)

        assertThat(readAll(at = 0)).isEqualTo(copyA)
        assertThat(readAll(at = 2_000_000)).isEqualTo(copyA.copyOfRange(2_000_000, copyA.size))
        assertThat(readAll(at = 500_000)).isEqualTo(copyA.copyOfRange(500_000, copyA.size))

        assertThat(host.requests.get()).isEqualTo(1)
    }

    /** The agreed price of one copy: a seek past what has arrived waits for it. */
    @Test
    fun a_read_ahead_of_the_download_waits_for_it_rather_than_fetching() {
        host.serve(copyA, bytesPerRead = 16 * 1024, pauseMillis = 2)

        val tail = readAll(at = copyA.size - 100_000L)

        assertThat(tail).isEqualTo(copyA.copyOfRange(copyA.size - 100_000, copyA.size))
        assertThat(host.requests.get()).isEqualTo(1)
    }

    @Test
    fun an_interrupted_download_carries_on_when_the_same_copy_comes_back() {
        host.serve(copyA, failFirstRequestAfter = 1_200_000)

        downloader.ensure(KEY, URI)

        awaitState(StreamDownloader.State.Complete)
        // Two requests - the one that died, and the resumption - and no starting over.
        assertThat(host.requests.get()).isEqualTo(2)
        assertThat(cachedBytes()).isEqualTo(copyA)
        assertThat(log.snapshot()).contains("DOWNLOAD_RESUME")
        assertThat(log.snapshot()).doesNotContain("COPY_CHANGED")
    }

    /**
     * The copy changing across an interruption, at the same length - the case a length comparison
     * alone cannot see, and exactly what the phone's two afternoon requests did.
     */
    @Test
    fun an_interrupted_download_that_comes_back_different_starts_over_on_one_copy() {
        val replaced = CopyOnWriteArrayList<String>()
        downloader.onCopyReplaced = { key, _, _ -> replaced += key }
        host.serve(copyA, failFirstRequestAfter = 1_200_000, laterRequestsServe = copyB)

        downloader.ensure(KEY, URI)

        awaitState(StreamDownloader.State.Complete)
        // All of the second copy and none of the first - no join anywhere in the file.
        assertThat(cachedBytes()).isEqualTo(copyB)
        assertThat(replaced).containsExactly(KEY)
        assertThat(log.snapshot()).contains("reason=bytes")
    }

    @Test
    fun a_copy_of_a_different_length_is_caught_before_reading_a_byte_of_it() {
        val shorter = copyA.copyOfRange(0, copyA.size - 300_000)
        host.serve(copyA, failFirstRequestAfter = 1_200_000, laterRequestsServe = shorter)

        downloader.ensure(KEY, URI)

        awaitState(StreamDownloader.State.Complete)
        assertThat(cachedBytes()).isEqualTo(shorter)
        assertThat(log.snapshot()).contains("reason=length")
    }

    /** The same kind of failure a direct HTTP read raised, so recovery and messaging keep working. */
    @Test
    fun a_failing_download_reaches_playback_as_a_network_error() {
        host.serve(copyA, alwaysFail = true)

        val error = assertThrows(HttpDataSource.HttpDataSourceException::class.java) { readAll(at = 0) }

        assertThat(error.reason).isEqualTo(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
    }

    /**
     * After a dropped connection the download waits out a backoff before trying again. Pressing play
     * or seeking in that window is a request to try now - not another error until the timer runs out.
     */
    @Test
    fun playback_reopening_after_a_failure_retries_at_once_instead_of_waiting_out_the_backoff() {
        downloader = StreamDownloader(cache, host, log = log, retryDelaysMillis = listOf(60_000L))
        host.serve(copyA, alwaysFail = true)
        downloader.ensure(KEY, URI)
        awaitFailed()

        host.serve(copyA)
        downloader.ensure(KEY, URI)

        // Well inside the 60s backoff it would otherwise have sat out.
        awaitState(StreamDownloader.State.Complete, timeoutMillis = 5_000)
        assertThat(cachedBytes()).isEqualTo(copyA)
    }

    @Test
    fun a_throttled_download_stays_a_little_ahead_of_playback() {
        throttled = true
        downloader = newDownloader(aheadBudget = 1024L * 1024)
        host.serve(copyA)
        downloader.ensure(KEY, URI)
        downloader.reportReadPosition(KEY, 0)

        Thread.sleep(500)

        // Held back near the budget - allowing for one fragment being written as it stopped.
        assertThat(cache.getCachedBytes(KEY, 0, C.LENGTH_UNSET.toLong()))
            .isAtMost(1024L * 1024 + StreamDownloader.FRAGMENT_BYTES)
        assertThat(downloader.stateOf(KEY)).isEqualTo(StreamDownloader.State.Running)

        // And let go as playback catches up.
        downloader.reportReadPosition(KEY, copyA.size.toLong())
        awaitState(StreamDownloader.State.Complete)
        assertThat(cachedBytes()).isEqualTo(copyA)
    }

    /**
     * Reported: on cellular with "only on wifi", every episode sat on a spinner. Playback can only
     * read what the download has committed to the cache, and it was committing nothing until the
     * whole file had arrived - so a throttled download, holding back because playback had read
     * nothing, waited on playback while playback waited on it. Unthrottled, the same fault cost a
     * wait for the entire file before the first note.
     */
    @Test
    fun playback_starts_from_a_throttled_download_long_before_it_finishes() {
        throttled = true
        downloader = newDownloader(aheadBudget = 1024L * 1024)
        host.serve(copyA)

        val whole = java.util.concurrent.Executors.newSingleThreadExecutor().submit<ByteArray> { readAll(at = 0) }

        assertThat(whole.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(copyA)
    }

    /**
     * Reported: on cellular with "only on wifi", skipping to the end of an episode left it on a
     * spinner, and nothing played again. The throttle measured "ahead" from the last byte playback
     * had *read*, but a seek moves playback without reading anything - so a seek past the download
     * left playback waiting for bytes the download would not fetch until playback read more. Each
     * waited on the other, forever.
     */
    @Test
    fun a_seek_past_a_throttled_download_is_not_left_waiting_on_it() {
        throttled = true
        downloader = newDownloader(aheadBudget = 1024L * 1024)
        host.serve(copyA)
        // Playing from the start, as far as the throttle lets the download get ahead.
        downloader.ensure(KEY, URI)
        downloader.reportReadPosition(KEY, 0)
        Thread.sleep(300)

        val tail = java.util.concurrent.Executors.newSingleThreadExecutor().submit<ByteArray> {
            readAll(at = copyA.size - 100_000L)
        }

        assertThat(tail.get(10, java.util.concurrent.TimeUnit.SECONDS))
            .isEqualTo(copyA.copyOfRange(copyA.size - 100_000, copyA.size))
    }

    @Test
    fun copies_cached_before_single_downloads_are_discarded_exactly_once() {
        host.serve(copyA)
        downloader.ensure(KEY, URI)
        awaitState(StreamDownloader.State.Complete)
        val marker = File(folder.root, "marker")

        MediaStorage.discardCopiesFromBeforeSingleDownload(cache, marker)
        assertThat(cache.keys).isEmpty()

        downloader.ensure(KEY, URI)
        awaitState(StreamDownloader.State.Complete)
        MediaStorage.discardCopiesFromBeforeSingleDownload(cache, marker)
        assertThat(cache.keys).containsExactly(KEY)
    }

    // ---- helpers ----

    private fun readAll(at: Long): ByteArray {
        val source = SingleCopyDataSource(cache, downloader, pollMillis = 5)
        source.open(DataSpec.Builder().setUri(URI).setKey(KEY).setPosition(at).build())
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = source.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, n)
            }
        } finally {
            source.close()
        }
        return out.toByteArray()
    }

    private fun cachedBytes(): ByteArray {
        val length = cache.getCachedBytes(KEY, 0, C.LENGTH_UNSET.toLong())
        val source = CacheDataSource(cache, PlaceholderDataSource.INSTANCE)
        source.open(DataSpec.Builder().setUri(Uri.EMPTY).setKey(KEY).setLength(length).build())
        val bytes = ByteArray(length.toInt())
        var read = 0
        while (read < bytes.size) read += source.read(bytes, read, bytes.size - read)
        source.close()
        return bytes
    }

    private fun awaitFailed() {
        val deadline = System.currentTimeMillis() + 5_000
        while (downloader.stateOf(KEY) !is StreamDownloader.State.Failed) {
            check(System.currentTimeMillis() < deadline) { "never failed: ${downloader.stateOf(KEY)}" }
            Thread.sleep(10)
        }
    }

    private fun awaitState(expected: StreamDownloader.State, timeoutMillis: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (downloader.stateOf(KEY) != expected) {
            check(System.currentTimeMillis() < deadline) { "still ${downloader.stateOf(KEY)}, wanted $expected\n${log.snapshot()}" }
            Thread.sleep(10)
        }
    }

    /**
     * Serves byte ranges of a "copy", one decision per request, as an ad-inserting host does - and
     * can drop a request partway or hand later requests a different copy.
     */
    private class FakeHost : DataSource.Factory {
        val requests = AtomicInteger()
        @Volatile private var first = ByteArray(0)
        @Volatile private var later: ByteArray? = null
        @Volatile private var failAfter = -1L
        @Volatile private var alwaysFail = false
        @Volatile private var bytesPerRead = Int.MAX_VALUE
        @Volatile private var pauseMillis = 0L

        fun serve(
            copy: ByteArray,
            failFirstRequestAfter: Long = -1,
            laterRequestsServe: ByteArray? = null,
            alwaysFail: Boolean = false,
            bytesPerRead: Int = Int.MAX_VALUE,
            pauseMillis: Long = 0
        ) {
            first = copy; later = laterRequestsServe; failAfter = failFirstRequestAfter
            this.alwaysFail = alwaysFail; this.bytesPerRead = bytesPerRead; this.pauseMillis = pauseMillis
        }

        override fun createDataSource(): DataSource = object : BaseDataSource(true) {
            private var body = ByteArray(0)
            private var pos = 0L
            private var end = 0L
            private var request = 0
            private var uri: Uri? = null

            override fun open(dataSpec: DataSpec): Long {
                request = requests.incrementAndGet()
                if (alwaysFail) throw IOException("no route to host")
                body = if (request == 1) first else (later ?: first)
                uri = dataSpec.uri
                pos = dataSpec.position
                // Like a real server: a range running past the end of this copy stops at its end.
                end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) body.size.toLong()
                else minOf(body.size.toLong(), pos + dataSpec.length)
                return end - pos
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (pos >= end) return C.RESULT_END_OF_INPUT
                if (request == 1 && failAfter >= 0 && pos >= failAfter) throw IOException("connection reset")
                if (pauseMillis > 0) Thread.sleep(pauseMillis)
                val n = minOf(length.toLong(), end - pos, bytesPerRead.toLong()).toInt()
                System.arraycopy(body, pos.toInt(), buffer, offset, n)
                pos += n
                return n
            }

            override fun getUri(): Uri? = uri
            override fun close() = Unit
        }
    }

    private companion object {
        const val KEY = "ep-1"
        val URI: Uri = Uri.parse("https://example.com/ep-1.mp3")
    }
}
