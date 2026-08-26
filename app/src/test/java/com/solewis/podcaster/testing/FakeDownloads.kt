package com.solewis.podcaster.testing

import com.solewis.podcaster.data.repo.DownloadStatus
import com.solewis.podcaster.data.repo.Downloads
import com.solewis.podcaster.data.repo.EpisodeDownload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    val requested = mutableListOf<String>()
    val removed = mutableListOf<String>()
    var bytesOnDisk = 0L

    override suspend fun downloadedBytes(): Long = bytesOnDisk

    override suspend fun download(episodeId: String) {
        requested += episodeId
    }

    override suspend fun remove(episodeId: String) {
        removed += episodeId
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
