package com.solewis.podcaster.player

import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
    private val scope: CoroutineScope,
    private val log: PlaybackLog? = null
) : MediaLibrarySession.Callback {

    private val tree = PodcastLibraryTree(podcastRepository, episodeRepository, queueRepository, log)

    // ---- who is talking to the session ----
    //
    // A morning drive had nine minutes where the car's skip buttons did nothing, right after
    // getting back in, and the log could not say why: it showed no skip arriving at all, but
    // everything from outside the app was attributed to the app's own package, so it could not
    // tell the car from the notification, nor say whether the car was connected. These four
    // record every controller arriving and leaving and everything each one asks for, so the next
    // time a button goes dead the log says whether the press reached the session, and from what.

    override fun onConnect(session: MediaSession, controller: ControllerInfo): MediaSession.ConnectionResult {
        log?.record("CONTROLLER_CONNECT", describe(controller))
        return super.onConnect(session, controller)
    }

    override fun onDisconnected(session: MediaSession, controller: ControllerInfo) {
        log?.record("CONTROLLER_DISCONNECT", describe(controller))
    }

    override fun onPlayerCommandRequest(session: MediaSession, controller: ControllerInfo, playerCommand: Int): Int {
        log?.record("SESSION_COMMAND", "command=${commandName(playerCommand)} ${describe(controller)}")
        return super.onPlayerCommandRequest(session, controller, playerCommand)
    }

    /** Hardware and Bluetooth buttons - the steering wheel included - arrive as these. */
    override fun onMediaButtonEvent(session: MediaSession, controller: ControllerInfo, intent: Intent): Boolean {
        val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        log?.record(
            "MEDIA_BUTTON",
            "key=${event?.let { KeyEvent.keyCodeToString(it.keyCode) }} action=${event?.action} ${describe(controller)}"
        )
        return super.onMediaButtonEvent(session, controller, intent)
    }

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

    internal companion object {
        /**
         * Package and uid, plus whether it came through the platform session - how Android Auto,
         * Bluetooth and the system UI usually reach a Media3 session, and why they can show up
         * under a placeholder package rather than their own.
         */
        fun describe(controller: ControllerInfo): String = buildString {
            append("from=").append(controller.packageName)
            append(" uid=").append(controller.uid)
            if (controller.controllerVersion == ControllerInfo.LEGACY_CONTROLLER_VERSION) append(" legacy")
            if (!controller.isTrusted) append(" untrusted")
        }

        fun commandName(command: Int): String = when (command) {
            Player.COMMAND_PLAY_PAUSE -> "PLAY_PAUSE"
            Player.COMMAND_PREPARE -> "PREPARE"
            Player.COMMAND_STOP -> "STOP"
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> "SEEK_TO_DEFAULT_POSITION"
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> "SEEK_IN_CURRENT_ITEM"
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> "SEEK_TO_PREVIOUS_ITEM"
            Player.COMMAND_SEEK_TO_PREVIOUS -> "SEEK_TO_PREVIOUS"
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> "SEEK_TO_NEXT_ITEM"
            Player.COMMAND_SEEK_TO_NEXT -> "SEEK_TO_NEXT"
            Player.COMMAND_SEEK_TO_MEDIA_ITEM -> "SEEK_TO_MEDIA_ITEM"
            Player.COMMAND_SEEK_BACK -> "SEEK_BACK"
            Player.COMMAND_SEEK_FORWARD -> "SEEK_FORWARD"
            Player.COMMAND_SET_SPEED_AND_PITCH -> "SET_SPEED"
            Player.COMMAND_SET_MEDIA_ITEM -> "SET_MEDIA_ITEM"
            Player.COMMAND_CHANGE_MEDIA_ITEMS -> "CHANGE_MEDIA_ITEMS"
            Player.COMMAND_SET_REPEAT_MODE -> "SET_REPEAT_MODE"
            Player.COMMAND_SET_SHUFFLE_MODE -> "SET_SHUFFLE_MODE"
            Player.COMMAND_SET_VOLUME -> "SET_VOLUME"
            Player.COMMAND_RELEASE -> "RELEASE"
            else -> "COMMAND_$command"
        }
    }
}
