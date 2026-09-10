package com.solewis.podcaster.data.repo

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.solewis.podcaster.download.PodcastDownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's view of Media3's download engine. [DownloadManager] owns the transfers and its own
 * index; this translates that into per-episode state the UI can render, and turns "download this
 * episode" into the request Media3 wants.
 *
 * The one detail that has to be right: the [DownloadRequest] id and `customCacheKey` are both the
 * episode id, matching what [com.solewis.podcaster.player.MediaItemMapper] puts on every
 * `MediaItem`. That is what lets the player find downloaded bytes without knowing a download
 * happened - see [com.solewis.podcaster.player.PlayerFactory.create]. Get it wrong and downloads
 * appear to work while playback silently streams anyway.
 */
@UnstableApi
class DownloadRepository(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadCache: Cache,
    private val episodeRepository: EpisodeRepository
) : Downloads {

    /**
     * Live per-episode download state, keyed by episode id. Absent means not downloaded.
     *
     * Two sources, because neither is sufficient alone: the index has every download including
     * finished ones but its progress figures lag, while `currentDownloads` has live progress but
     * only for what is in flight. The index provides the set, the in-flight list overrides it.
     *
     * Collect from the main thread - [DownloadManager] dispatches its callbacks on the thread it
     * was built on, which is the one that assembled [com.solewis.podcaster.AppContainer].
     */
    override fun observe(): Flow<Map<String, EpisodeDownload>> = channelFlow {
        suspend fun publish() = send(snapshot())

        val listener = object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) {
                launch { publish() }
            }

            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?
            ) {
                launch { publish() }
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                launch { publish() }
            }
        }
        downloadManager.addListener(listener)
        publish()

        // Progress is not an event: onDownloadChanged fires on state transitions, not per byte. So
        // poll, but only while something is actually moving - an idle library costs nothing.
        launch {
            while (true) {
                delay(PROGRESS_TICK_MILLIS)
                if (downloadManager.currentDownloads.isNotEmpty()) publish()
            }
        }

        awaitClose { downloadManager.removeListener(listener) }
    }

    /** Total bytes the downloads occupy on disk - the cache's own figure, not a sum of estimates. */
    override suspend fun downloadedBytes(): Long = withContext(Dispatchers.IO) { downloadCache.cacheSpace }

    override suspend fun isDownloaded(episodeId: String): Boolean = withContext(Dispatchers.IO) {
        downloadManager.downloadIndex.getDownload(episodeId)?.state == Download.STATE_COMPLETED
    }

    override suspend fun download(episodeId: String) {
        val episode = episodeRepository.getPlayableById(episodeId) ?: return
        val request = DownloadRequest.Builder(episodeId, Uri.parse(episode.mediaUrl))
            .setCustomCacheKey(episodeId)
            .build()
        // foreground = true: this is always a response to a tap, so the app is in the foreground and
        // startForegroundService is both permitted and correct. Passing false would route through
        // startService, which throws once the app is backgrounded mid-download.
        DownloadService.sendAddDownload(context, PodcastDownloadService::class.java, request, true)
    }

    override suspend fun remove(episodeId: String) {
        DownloadService.sendRemoveDownload(context, PodcastDownloadService::class.java, episodeId, true)
    }

    private suspend fun snapshot(): Map<String, EpisodeDownload> {
        val fromIndex = withContext(Dispatchers.IO) {
            buildMap {
                downloadManager.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) {
                        val download = cursor.download
                        put(download.request.id, download.toEpisodeDownload())
                    }
                }
            }
        }
        val inFlight = downloadManager.currentDownloads.associate {
            it.request.id to it.toEpisodeDownload()
        }
        return fromIndex + inFlight
    }

    private fun Download.toEpisodeDownload() = EpisodeDownload(
        episodeId = request.id,
        status = when (state) {
            Download.STATE_COMPLETED -> DownloadStatus.DOWNLOADED
            Download.STATE_DOWNLOADING -> DownloadStatus.DOWNLOADING
            Download.STATE_FAILED -> DownloadStatus.FAILED
            Download.STATE_REMOVING -> DownloadStatus.REMOVING
            // QUEUED, STOPPED and RESTARTING all mean the same thing to someone looking at a row:
            // it is going to happen, but it is not happening yet.
            else -> DownloadStatus.QUEUED
        },
        percent = percentDownloaded.takeIf { !it.isNaN() } ?: 0f,
        bytesDownloaded = bytesDownloaded
    )

    private companion object {
        const val PROGRESS_TICK_MILLIS = 1_000L
    }
}
