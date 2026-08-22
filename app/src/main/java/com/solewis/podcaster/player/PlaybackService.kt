package com.solewis.podcaster.player

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.solewis.podcaster.PodcasterApp

/**
 * Owns the single [ExoPlayer] and [MediaLibrarySession] for the app. `MediaLibraryService`
 * extends `MediaSessionService`, which extends `LifecycleService` as of Media3 1.10+ -
 * `super.onCreate()`/`onDestroy()` must be called, unlike the pre-1.10 tutorials most Media3
 * examples online are still based on. That base class is also where `lifecycleScope` (used for
 * [ProgressWriter]'s coroutines) comes from.
 *
 * `MediaLibraryService` (rather than a plain `MediaSessionService`) is what lets Android Auto -
 * or any other browser client - see a content tree at all: see
 * [PodcastLibrarySessionCallback] for the browse tree and the resume-position handling that
 * makes tapping an episode from Auto's grid actually resume where you left off.
 *
 * `DefaultMediaNotificationProvider` (Media3's built-in default) handles the notification
 * entirely from the `MediaMetadata` set on each `MediaItem` - no custom notification code needed
 * here. Artwork is fetched via `DataSourceBitmapLoader` (`androidx.media3.datasource`, not
 * `androidx.media3.session` as in older tutorials) built through its `Builder` - its direct
 * constructors are deprecated in this Media3 version.
 */
class PlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession
    private lateinit var progressWriter: ProgressWriter

    override fun onCreate() {
        super.onCreate()
        val container = (application as PodcasterApp).container
        player = PlayerFactory.create(this, container.mediaCache)

        val callback = PodcastLibrarySessionCallback(
            podcastRepository = container.podcastRepository,
            episodeRepository = container.episodeRepository,
            queueRepository = container.queueRepository,
            scope = lifecycleScope
        )
        mediaSession = MediaLibrarySession.Builder(this, player, callback)
            .setBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader.Builder(this).build()))
            .setMediaButtonPreferences(skipButtonPreferences())
            .build()

        progressWriter = ProgressWriter(player, container.episodeRepository, lifecycleScope)
        player.addListener(progressWriter)
        player.addListener(AutoAdvancer(player, container.queueRepository, lifecycleScope))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = mediaSession

    /**
     * Without this, the system notification and Android Auto fall back to their own default
     * button set - generic rewind/skip-to-previous glyphs with no indication of how far they
     * seek, since there's no real "previous track" (every episode plays standalone; see
     * [AutoAdvancer]). `ICON_SKIP_BACK_15`/`ICON_SKIP_FORWARD_15` are Media3's built-in icons for
     * exactly this - a podcast-style timed skip - matching [PlayerFactory]'s 15s seek increments
     * and the in-app Now Playing screen's own skip buttons.
     */
    private fun skipButtonPreferences(): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setDisplayName("Back 15 seconds")
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_15)
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setDisplayName("Forward 15 seconds")
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
    )

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!player.isPlaying) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        progressWriter.flushBlocking()
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}
