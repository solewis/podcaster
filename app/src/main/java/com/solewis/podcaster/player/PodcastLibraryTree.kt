package com.solewis.podcaster.player

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.solewis.podcaster.data.db.model.SortOrder
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.PodcastRepository
import com.solewis.podcaster.data.repo.QueueRepository
import kotlinx.coroutines.flow.first

/**
 * The actual Android Auto browse-tree logic - kept free of `MediaLibrarySession`/`ControllerInfo`/
 * `LibraryParams` (unlike [PodcastLibrarySessionCallback], which wraps this for Media3) so it can
 * be exercised directly in a test against a real (in-memory) Room database, the same way
 * `EpisodeDaoTest` does, rather than needing a full `MediaBrowser`/`MediaSession` binder
 * round-trip just to prove the tree shape and resume-position logic are correct.
 *
 * Root deliberately has only two children (`Up Next`, `Shows`) rather than one per subscription:
 * Android Auto renders a browsable node's *direct* children as a persistent top tab strip, so
 * putting every subscribed show at the root - as an earlier version of this tree did - turned
 * into one tab per show, which doesn't scale past a handful of subscriptions. Grouping shows
 * under one "Shows" folder keeps the tab strip to two tabs; the shows themselves render as a
 * grid one level in (see the content-style hints in [PodcastLibrarySessionCallback.onGetLibraryRoot]).
 */
class PodcastLibraryTree(
    private val podcastRepository: PodcastRepository,
    private val episodeRepository: EpisodeRepository,
    private val queueRepository: QueueRepository
) {
    suspend fun rootChildren(): List<MediaItem> = listOf(
        MediaItemMapper.toBrowsableMediaItem(QUEUE_ID, "Up Next"),
        MediaItemMapper.toBrowsableMediaItem(SHOWS_ID, "Shows")
    )

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT_ID -> rootChildren()
        parentId == QUEUE_ID -> queueRepository.getPlayableQueue().map(MediaItemMapper::toMediaItem)
        parentId == SHOWS_ID -> showsChildren()
        parentId.startsWith(PODCAST_PREFIX) -> podcastChildren(parentId.removePrefix(PODCAST_PREFIX).toLongOrNull())
        else -> emptyList()
    }

    private suspend fun showsChildren(): List<MediaItem> =
        podcastRepository.observeAll().first().map {
            MediaItemMapper.toBrowsableMediaItem("$PODCAST_PREFIX${it.id}", it.title, it.artworkUrl)
        }

    suspend fun item(mediaId: String): MediaItem? =
        episodeRepository.getPlayableById(mediaId)?.let(MediaItemMapper::toMediaItem)

    /**
     * See [PodcastLibrarySessionCallback.onSetMediaItems] - this is what makes an episode tapped
     * from Auto's browse tree resume instead of restarting from 0.
     */
    suspend fun resolveForPlayback(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): MediaItemsWithStartPosition {
        val resolved = mediaItems.map { item ->
            episodeRepository.getPlayableById(item.mediaId)?.let(MediaItemMapper::toMediaItem) ?: item
        }
        val resolvedStartPositionMs = if (mediaItems.size == 1) {
            episodeRepository.getPlayableById(mediaItems[0].mediaId)?.startPositionMillis ?: startPositionMs
        } else {
            startPositionMs
        }
        return MediaItemsWithStartPosition(resolved, startIndex, resolvedStartPositionMs)
    }

    private suspend fun podcastChildren(podcastId: Long?): List<MediaItem> {
        if (podcastId == null) return emptyList()
        val sortOrder = podcastRepository.observeById(podcastId).first()?.sortOrder ?: SortOrder.NEWEST_FIRST
        return episodeRepository.getPlayableEpisodesForPodcast(podcastId, sortOrder).map(MediaItemMapper::toMediaItem)
    }

    companion object {
        const val ROOT_ID = "root"
        const val QUEUE_ID = "queue"
        const val SHOWS_ID = "shows"
        const val PODCAST_PREFIX = "podcast:"
    }
}
