package com.solewis.podcaster.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.PlaceholderDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import java.io.IOException
import java.io.InterruptedIOException

/**
 * How playback reads a streamed episode: from the stream cache only, never from the network.
 *
 * The network is [StreamDownloader]'s alone, so that one listen is one copy of the file - see its
 * doc for the phone log that made that the requirement. Opening this starts (or continues) that
 * download; reading past what it has delivered waits for it. That wait is the price of a big seek
 * early in an episode, agreed knowingly: the download runs at many times real time, so in practice
 * it is a few seconds, and the player's own buffering indicator covers it.
 *
 * Reads are bounded to bytes already known to be cached, which matters beyond tidiness: a cache
 * read that touched an uncached range would lock it, and the download would then block on writing
 * the very bytes this is waiting for.
 *
 * A failed download surfaces as a network error, the same kind a direct HTTP read raised, so the
 * existing recovery ([PlaybackErrorRetrier]) and messaging carry on working unchanged.
 */
@UnstableApi
class SingleCopyDataSource(
    private val cache: Cache,
    private val downloader: StreamDownloader,
    private val pollMillis: Long = POLL_MILLIS
) : BaseDataSource(/* isNetwork = */ true) {

    private var dataSpec: DataSpec? = null
    private var key: String = ""
    private var position = 0L
    private var bytesRemaining = 0L
    private var reader: CacheDataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        this.dataSpec = dataSpec
        key = dataSpec.key ?: dataSpec.uri.toString()
        position = dataSpec.position
        downloader.ensure(key, dataSpec.uri)

        val total = awaitContentLength()
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            total != C.LENGTH_UNSET.toLong() -> (total - position).coerceAtLeast(0)
            else -> C.LENGTH_UNSET.toLong()
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        while (true) {
            reader?.let { open ->
                val wanted = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
                val read = open.read(buffer, offset, wanted)
                if (read != C.RESULT_END_OF_INPUT) {
                    position += read
                    if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
                    downloader.reportReadPosition(key, position)
                    bytesTransferred(read)
                    return read
                }
                open.close()
                reader = null
            }

            val available = cache.getCachedLength(key, position, C.LENGTH_UNSET.toLong())
            if (available > 0) {
                reader = CacheDataSource(cache, PlaceholderDataSource.INSTANCE).also {
                    it.open(DataSpec.Builder().setUri(Uri.EMPTY).setKey(key).setPosition(position).setLength(available).build())
                }
                continue
            }

            when (val state = downloader.stateOf(key)) {
                // Everything there is has been read.
                StreamDownloader.State.Complete -> if (bytesRemaining == C.LENGTH_UNSET.toLong() || isEndOfContent()) {
                    return C.RESULT_END_OF_INPUT
                } else {
                    // Finished, yet the bytes here are gone - cleared or evicted under us.
                    downloader.ensure(key, dataSpec!!.uri)
                }
                is StreamDownloader.State.Failed -> throw networkError(state.error)
                // Not ours any more, e.g. another episode took the download over.
                null -> downloader.ensure(key, dataSpec!!.uri)
                StreamDownloader.State.Running -> Unit
            }
            pause()
        }
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        reader?.close()
        reader = null
        if (dataSpec != null) {
            dataSpec = null
            transferEnded()
        }
    }

    /**
     * The file's total length, once the download's first response has said - which the player
     * needs to build an MP3's seek map. A response that never states a length (chunked transfer)
     * leaves this unknown, and reading then runs until the download completes.
     */
    private fun awaitContentLength(): Long {
        while (true) {
            val known = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            if (known != C.LENGTH_UNSET.toLong()) return known
            when (val state = downloader.stateOf(key)) {
                is StreamDownloader.State.Failed -> throw networkError(state.error)
                StreamDownloader.State.Complete -> return cache.getCachedBytes(key, 0, C.LENGTH_UNSET.toLong())
                StreamDownloader.State.Running -> if (cache.getCachedLength(key, position, 1) > 0) return C.LENGTH_UNSET.toLong()
                null -> downloader.ensure(key, dataSpec!!.uri)
            }
            pause()
        }
    }

    private fun isEndOfContent(): Boolean {
        val total = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return total != C.LENGTH_UNSET.toLong() && position >= total
    }

    /** Interruptible, because that is how the player cancels a load when you seek elsewhere. */
    private fun pause() {
        try {
            Thread.sleep(pollMillis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("cancelled while waiting for the download")
        }
    }

    private fun networkError(cause: IOException): IOException = HttpDataSource.HttpDataSourceException(
        cause,
        dataSpec ?: DataSpec(Uri.EMPTY),
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        HttpDataSource.HttpDataSourceException.TYPE_READ
    )

    class Factory(private val cache: Cache, private val downloader: StreamDownloader) : DataSource.Factory {
        override fun createDataSource(): DataSource = SingleCopyDataSource(cache, downloader)
    }

    private companion object {
        const val POLL_MILLIS = 50L
    }
}
