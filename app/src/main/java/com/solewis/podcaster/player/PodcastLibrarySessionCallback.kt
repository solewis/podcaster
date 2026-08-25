package com.solewis.podcaster.player

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.PodcastRepository
import com.solewis.podcaster.data.repo.QueueRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future

/**
 * Thin Media3 adapter around [PodcastLibraryTree] - exposes a browsable tree (subscribed shows
 * and a personal "Up Next" queue) to external controllers such as Android Auto, which needs
 * content to display in the car's media grid rather than only transport controls. The app's own
 * UI never goes through here: it drives playback directly via [PlayerConnection] with
 * fully-known [com.solewis.podcaster.data.repo.PlayableEpisode]s.
 *
 * [onSetMediaItems] is the piece that matters most for correctness - see [PodcastLibraryTree.resolveForPlayback].
 */
@UnstableApi
class PodcastLibrarySessionCallback(
    podcastRepository: PodcastRepository,
    episodeRepository: EpisodeRepository,
    queueRepository: QueueRepository,
    private val scope: CoroutineScope
) : MediaLibrarySession.Callback {

    private val tree = PodcastLibraryTree(podcastRepository, episodeRepository, queueRepository)

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val rootParams = LibraryParams.Builder().build().also {
            it.extras.putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            )
            it.extras.putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            )
        }
        val root = MediaItemMapper.toBrowsableMediaItem(PodcastLibraryTree.ROOT_ID, "Podcaster")
        return Futures.immediateFuture(LibraryResult.ofItem(root, rootParams))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        LibraryResult.ofItemList(tree.children(parentId), params)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
            ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }

    /**
     * What a Bluetooth remote, a steering-wheel play button, or System UI's post-reboot
     * resumption notification gets when it asks to resume with the app not running. Without it
     * the session comes up with an empty playlist and pressing play in the car does nothing -
     * the same "the player is just gone" symptom the phone UI had, one layer out.
     *
     * The three-argument overload, not the two-argument one Media3 deprecated in favour of it.
     * [isForPlayback] `false` means the caller only wants metadata to render a resumption
     * notification rather than to start playing, which the same single item answers either way.
     */
    override fun onPlaybackResumption(
        session: MediaSession,
        controller: ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
        // A failed future is how Media3 is told there is nothing to resume. Returning an empty
        // playlist instead leaves the session prepared with no items and the play button dead.
        tree.lastPlayed() ?: throw UnsupportedOperationException("No listening history to resume")
    }

    override fun onSetMediaItems(
        session: MediaSession,
        controller: ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
        tree.resolveForPlayback(mediaItems, startIndex, startPositionMs)
    }
}
