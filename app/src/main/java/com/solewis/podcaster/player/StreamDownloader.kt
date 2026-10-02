package com.solewis.podcaster.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.PlaceholderDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.min

/**
 * Fetches the episode being played into the stream cache in a single request, start to finish, so
 * that everything a listen plays comes from one copy of the file.
 *
 * Why one copy matters: hosts that insert ads per request hand out a differently stitched file each
 * time, and the show's audio sits at different byte offsets in each. Confirmed from a phone's cache:
 * a seek cancelled the request that was streaming, the next request got a copy whose audio was
 * shifted by 17,991,031 bytes, and the cache joined the two at 70:24 - so the clock kept running
 * while the audio jumped back seven and a half minutes. The old prefetcher was meant to prevent
 * that and could not: it opened its own request alongside playback's, into the same cache entry,
 * and was only ever started from the in-app play button.
 *
 * So this is now the *only* thing that fetches a streamed episode. Playback reads exclusively from
 * the cache through [SingleCopyDataSource], waiting when it gets ahead of the download - a seek past
 * what has arrived waits for it rather than opening a second request. The download is independent
 * of playback's reads, so seeking never cancels it.
 *
 * A download can still be interrupted (dropped connection, killed process), and resuming it is a
 * new request. Before writing anything from one, [JoinCheckingDataSource] compares the response's
 * total length and the last [OVERLAP_BYTES] already cached against the same bytes fetched again. A
 * match means the same copy, and the download simply carries on. A mismatch means a different copy:
 * the partial one is discarded, the download restarts from the beginning, and [onCopyReplaced]
 * lets playback reload onto the new copy. The comparison is worth making rather than assuming a
 * new copy every time - on the phone, two requests 7½ hours apart joined perfectly, and hosts that
 * do not insert ads always do - and it is the only way to know the old position is still valid.
 *
 * One download at a time, for the episode currently being played; starting another cancels it.
 */
@UnstableApi
class StreamDownloader(
    private val cache: Cache,
    private val upstreamFactory: DataSource.Factory,
    private val log: PlaybackLog? = null,
    private val retryDelaysMillis: List<Long> = DEFAULT_RETRY_DELAYS_MILLIS,
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "stream-download").apply { isDaemon = true }
    }
) {
    sealed interface State {
        data object Running : State
        data object Complete : State
        data class Failed(val error: IOException) : State
    }

    /**
     * Called on the download thread when an interrupted download resumed onto a different copy, after
     * the old copy is gone. Lengths are in bytes, either possibly [C.LENGTH_UNSET].
     */
    @Volatile
    var onCopyReplaced: ((key: String, oldLength: Long, newLength: Long) -> Unit)? = null

    private val lock = Any()
    private var job: Job? = null

    /**
     * Makes sure [key] is downloading or downloaded. Cheap and idempotent - called on every open of
     * the playback data source, including after every seek.
     */
    fun ensure(key: String, uri: Uri) {
        synchronized(lock) {
            val current = job
            if (current != null && current.key == key && !current.cancelled) {
                when (current.state) {
                    State.Running -> return
                    // A finished download stays finished only while its bytes are still there -
                    // the cache can be cleared from Settings, or evicted, under a loaded episode.
                    State.Complete -> if (isFullyCached(key)) return
                    // Waiting out a backoff after a failure. Playback opening the source again is
                    // someone pressing play or seeking (or the error retrier acting for them), and
                    // that means try now - not error again until a timer up to 30s away runs out.
                    is State.Failed -> Unit
                }
            }
            current?.cancel()
            job = Job(key, uri).also { it.future = executor.submit(it) }
        }
    }

    /** What the download for [key] is doing, or null when nothing is downloading it. */
    fun stateOf(key: String): State? = synchronized(lock) { job?.takeIf { it.key == key && !it.cancelled }?.state }

    fun cancel() {
        synchronized(lock) {
            job?.cancel()
            job = null
        }
    }

    private fun isFullyCached(key: String): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length != C.LENGTH_UNSET.toLong() && cache.getCachedLength(key, 0, length) == length
    }

    private inner class Job(val key: String, val uri: Uri) : Runnable {
        @Volatile var cancelled = false
        @Volatile var state: State = State.Running
        @Volatile private var writer: CacheWriter? = null
        var future: Future<*>? = null

        fun cancel() {
            cancelled = true
            writer?.cancel()
            future?.cancel(true)
        }

        override fun run() {
            var attempt = 0
            while (!cancelled) {
                try {
                    discardAnythingButACleanPrefix()
                    val dataSource = CacheDataSource(
                        cache,
                        JoinCheckingDataSource(upstreamFactory.createDataSource(), this),
                        FileDataSource(),
                        CacheDataSink(cache, FRAGMENT_BYTES),
                        // Blocking rather than streaming around a locked range: a request that
                        // read without writing would be fetching bytes nobody keeps.
                        CacheDataSource.FLAG_BLOCK_ON_CACHE,
                        null
                    )
                    // FLAG_ALLOW_CACHE_FRAGMENTATION is what makes CacheDataSink honour FRAGMENT_BYTES at all;
                    // without it the sink writes one file and commits it only when the download ends.
                    // Playback can read nothing until a fragment is committed, so the whole episode
                    // had to arrive before a note played. Found when a download that was being held
                    // back - a since-removed setting - never arrived at all: a spinner on every
                    // episode.
                    val spec = DataSpec.Builder()
                        .setUri(uri)
                        .setKey(key)
                        .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                        .build()
                    val newWriter = CacheWriter(dataSource, spec, null, null)
                    writer = newWriter
                    if (cancelled) return
                    log?.record("DOWNLOAD_START", "item=$key cached=${cache.getCachedBytes(key, 0, C.LENGTH_UNSET.toLong())}")
                    newWriter.cache()
                    state = State.Complete
                    log?.record("DOWNLOAD_COMPLETE", "item=$key bytes=${cache.getCachedBytes(key, 0, C.LENGTH_UNSET.toLong())}")
                    return
                } catch (e: CopyChangedException) {
                    log?.record("COPY_CHANGED", "item=$key oldLen=${e.oldLength} newLen=${e.newLength} reason=${e.reason}")
                    cache.removeResource(key)
                    onCopyReplaced?.invoke(key, e.oldLength, e.newLength)
                    attempt = 0
                } catch (e: IOException) {
                    if (cancelled || e is InterruptedIOException) return
                    state = State.Failed(e)
                    log?.record("DOWNLOAD_FAILED", "item=$key attempt=$attempt ${e.javaClass.simpleName}: ${e.message}")
                    try {
                        Thread.sleep(retryDelaysMillis[min(attempt, retryDelaysMillis.lastIndex)])
                    } catch (_: InterruptedException) {
                        return
                    }
                    attempt++
                    state = State.Running
                } catch (_: InterruptedException) {
                    return
                }
            }
        }

        /**
         * Only a contiguous run from byte 0 can be resumed with a join check. Anything else - spans
         * past a gap, left by the old streaming path - is of unknown origin, so it goes.
         */
        private fun discardAnythingButACleanPrefix() {
            val total = cache.getCachedBytes(key, 0, C.LENGTH_UNSET.toLong())
            if (total <= 0) return
            val prefix = cache.getCachedLength(key, 0, C.LENGTH_UNSET.toLong()).coerceAtLeast(0)
            if (prefix != total) {
                log?.record("DOWNLOAD_DISCARD", "item=$key prefix=$prefix cached=$total")
                cache.removeResource(key)
            }
        }
    }

    /** The upstream for the single download: checks the join when resuming. */
    private inner class JoinCheckingDataSource(
        private val upstream: DataSource,
        private val job: Job
    ) : DataSource by upstream {

        /** Bytes still owed to the cache, when it asked for a bounded range; otherwise unset. */
        private var remaining = C.LENGTH_UNSET.toLong()

        override fun open(dataSpec: DataSpec): Long {
            remaining = C.LENGTH_UNSET.toLong()
            if (dataSpec.position == 0L) return upstream.open(dataSpec)

            // Resuming: fetch the end of what we already have again, alongside what follows it.
            //
            // Unbounded, whatever the cache asked for. Once it knows the old copy's length it asks
            // for exactly the rest of *that* copy, and a bounded request's open() only echoes the
            // requested length back - so comparing it would compare the old length with itself, and
            // a shorter copy would pass. Unbounded, the server states its own remainder.
            val overlap = min(OVERLAP_BYTES.toLong(), dataSpec.position)
            val start = dataSpec.position - overlap
            val opened = upstream.open(dataSpec.buildUpon().setPosition(start).setLength(C.LENGTH_UNSET.toLong()).build())

            val cachedTotal = ContentMetadata.getContentLength(cache.getContentMetadata(job.key))
            val freshTotal = if (opened == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else start + opened
            if (cachedTotal != C.LENGTH_UNSET.toLong() && freshTotal != C.LENGTH_UNSET.toLong() && cachedTotal != freshTotal) {
                upstream.close()
                throw CopyChangedException(cachedTotal, freshTotal, "length")
            }
            val fresh = readFully(upstream, overlap.toInt())
            if (!fresh.contentEquals(readCached(job.key, start, overlap.toInt()))) {
                upstream.close()
                throw CopyChangedException(cachedTotal, freshTotal, "bytes")
            }
            log?.record("DOWNLOAD_RESUME", "item=${job.key} at=${dataSpec.position} joinChecked=$overlap")
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                remaining = dataSpec.length
                return dataSpec.length
            }
            return if (opened == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else opened - overlap
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val wanted = if (remaining == C.LENGTH_UNSET.toLong()) length else min(length.toLong(), remaining).toInt()
            val read = upstream.read(buffer, offset, wanted)
            if (read > 0 && remaining != C.LENGTH_UNSET.toLong()) remaining -= read
            return read
        }

    }

    private fun readFully(source: DataSource, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = source.read(bytes, read, length - read)
            if (n == C.RESULT_END_OF_INPUT) throw IOException("stream ended inside the join check")
            read += n
        }
        return bytes
    }

    private fun readCached(key: String, position: Long, length: Int): ByteArray {
        // Bounded to bytes known to be cached, so this never takes a lock on a hole.
        val source = CacheDataSource(cache, PlaceholderDataSource.INSTANCE)
        try {
            source.open(DataSpec.Builder().setUri(Uri.EMPTY).setKey(key).setPosition(position).setLength(length.toLong()).build())
            return readFully(source, length)
        } finally {
            source.close()
        }
    }

    private class CopyChangedException(val oldLength: Long, val newLength: Long, val reason: String) :
        IOException("a different copy of the file came back ($reason)")

    companion object {
        /**
         * How much is fetched twice to check a resumed download's join. Large enough that two
         * different stitchings cannot plausibly agree across all of it.
         */
        const val OVERLAP_BYTES = 64 * 1024

        /**
         * How often written data becomes readable: a span is only visible once its fragment is
         * committed, so this bounds both the first-audio delay and how far a waiting reader lags.
         * 512KB is under a second on a weak connection and a few hundred files for a long episode.
         */
        const val FRAGMENT_BYTES = 512L * 1024

        val DEFAULT_RETRY_DELAYS_MILLIS = listOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L)
    }
}
