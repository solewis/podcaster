package com.solewis.podcaster.player

import android.content.Intent
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.solewis.podcaster.PodcasterApp

/**
 * Owns the single [ExoPlayer] and [MediaSession] for the app. `MediaSessionService` extends
 * `LifecycleService` as of Media3 1.10+ - `super.onCreate()`/`onDestroy()` must be called, unlike
 * the pre-1.10 tutorials most Media3 examples online are still based on.
 *
 * `DefaultMediaNotificationProvider` (Media3's built-in default) handles the notification
 * entirely from the `MediaMetadata` set on each `MediaItem` - no custom notification code needed
 * here. Artwork is fetched via `DataSourceBitmapLoader` (`androidx.media3.datasource`, not
 * `androidx.media3.session` as in older tutorials) built through its `Builder` - its direct
 * constructors are deprecated in this Media3 version.
 */
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        val container = (application as PodcasterApp).container
        player = PlayerFactory.create(this, container.mediaCache)

        mediaSession = MediaSession.Builder(this, player)
            .setBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader.Builder(this).build()))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!player.isPlaying) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}
