package com.solewis.podcaster.data.repo

import kotlinx.coroutines.flow.Flow

/** What the UI needs to know about one episode's download. */
data class EpisodeDownload(
    val episodeId: String,
    val status: DownloadStatus,
    /** 0..100. Meaningful only while [status] is [DownloadStatus.DOWNLOADING]. */
    val percent: Float = 0f,
    val bytesDownloaded: Long = 0
)

enum class DownloadStatus {
    /** Accepted, but not moving yet - behind another download, or waiting for a network. */
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,

    /** Being deleted. Distinct from absent, so a row can show the deletion rather than flicker. */
    REMOVING
}

/**
 * Everything the UI is allowed to do to downloads.
 *
 * An interface for the same reason [com.solewis.podcaster.player.Playback] is one: the real
 * implementation is a thin shell over Media3's `DownloadManager`, which cannot be built on the JVM
 * and owns real files on disk. Without this seam, no download state a screen renders - the progress
 * ring, the delete confirmation, the running total - would be reachable from a test.
 */
interface Downloads {

    /** Live state keyed by episode id. An absent key means not downloaded. */
    fun observe(): Flow<Map<String, EpisodeDownload>>

    /** Total bytes downloads occupy on disk. */
    suspend fun downloadedBytes(): Long

    /**
     * A direct lookup rather than a read of [observe], because the caller that matters - deciding
     * whether an episode can play without a network - needs an answer now, not a subscription.
     */
    suspend fun isDownloaded(episodeId: String): Boolean

    suspend fun download(episodeId: String)

    suspend fun remove(episodeId: String)
}
