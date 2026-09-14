package com.solewis.podcaster.testing

import com.solewis.podcaster.data.repo.DownloadStatus
import com.solewis.podcaster.data.repo.Downloads
import com.solewis.podcaster.data.repo.EpisodeDownload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [Downloads] that records what it was asked to do and lets a test drive download state directly.
 *
 * Note that [download] deliberately does *not* mark the episode downloaded. A real download is tens
 * of megabytes over a phone connection - it is queued, then progressing, then finished, and a fake
 * that jumped straight to finished would hide exactly the in-between states the progress ring and
 * the cancel affordance exist for.
 */
class FakeDownloads : Downloads {

    private val _states = MutableStateFlow<Map<String, EpisodeDownload>>(emptyMap())
    override fun observe(): Flow<Map<String, EpisodeDownload>> = _states.asStateFlow()

    /**
     * Copy-on-write because these are written from the code under test and read from a polling
     * assertion, on different threads. A plain ArrayList threw ConcurrentModificationException out
     * of `awaitTrue`'s predicate - not often, and never in the test that caused it, since the
     * reader is whoever happens to be waiting.
     */
    val requested: MutableList<String> = CopyOnWriteArrayList()
    val removed: MutableList<String> = CopyOnWriteArrayList()
    var bytesOnDisk = 0L

    override suspend fun downloadedBytes(): Long = bytesOnDisk

    override suspend fun isDownloaded(episodeId: String): Boolean =
        _states.value[episodeId]?.status == DownloadStatus.DOWNLOADED

    override suspend fun download(episodeId: String) {
        requested += episodeId
    }

    override suspend fun remove(episodeId: String) {
        removed += episodeId
    }

    override suspend fun removeAll() {
        removed += _states.value.keys
        _states.value = emptyMap()
    }

    // ---- driving download state from a test ----

    fun emit(
        episodeId: String,
        status: DownloadStatus,
        percent: Float = 0f,
        bytesDownloaded: Long = 0
    ) {
        _states.value = _states.value + (episodeId to EpisodeDownload(episodeId, status, percent, bytesDownloaded))
    }

    /** As if the download were deleted, or had never been asked for. */
    fun forget(episodeId: String) {
        _states.value = _states.value - episodeId
    }
}
