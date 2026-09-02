package com.solewis.podcaster.player

import android.app.PendingIntent
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
import com.solewis.podcaster.MainActivity
import com.solewis.podcaster.PodcasterApp
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.settings.AppSettings
import com.solewis.podcaster.data.settings.SkipAmount
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
    private lateinit var sessionPlayer: TimedSkipPlayer
    private lateinit var mediaSession: MediaLibrarySession
    private lateinit var progressWriter: ProgressWriter

    override fun onCreate() {
        super.onCreate()
        val container = (application as PodcasterApp).container
        player = PlayerFactory.create(this, container.downloadCache, container.streamCache)
        // Before the persister is attached, so reading the saved speed back doesn't immediately
        // rewrite it. This is the only place speed is applied, which is what makes it hold for
        // playback started from Android Auto or a media button as well as from the app's own UI.
        player.setPlaybackSpeed(container.settings.speed)
        player.addListener(SpeedPersister(container.settings))
        // Only the session sees the wrapper - it exists purely to expose the 15s seeks as
        // next/previous for external controllers. ProgressWriter and AutoAdvancer below stay on
        // the real ExoPlayer, since they care about actual playlist and position semantics.
        sessionPlayer = TimedSkipPlayer(
            player,
            skipBackMillis = { container.settings.skipBack.millis },
            skipForwardMillis = { container.settings.skipForward.millis }
        )

        val callback = PodcastLibrarySessionCallback(
            podcastRepository = container.podcastRepository,
            episodeRepository = container.episodeRepository,
            queueRepository = container.queueRepository,
            scope = lifecycleScope
        )
        mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, callback)
            .setSessionActivity(nowPlayingIntent())
            .setBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader.Builder(this).build()))
            .setMediaButtonPreferences(
                skipButtonPreferences(container.settings.skipBack, container.settings.skipForward)
            )
            .build()

        // The button *icons* carry the number, so they have to be re-pushed when the setting
        // changes - unlike the seek itself, which TimedSkipPlayer reads fresh on every press.
        lifecycleScope.launch {
            container.settings.observe()
                .map { it.skipBack to it.skipForward }
                .distinctUntilChanged()
                .collect { (back, forward) ->
                    mediaSession.setMediaButtonPreferences(skipButtonPreferences(back, forward))
                }
        }

        seedLastPlayedEpisode(container.episodeRepository)

        progressWriter = ProgressWriter(player, container.episodeRepository, lifecycleScope)
        player.addListener(progressWriter)
        player.addListener(
            AutoAdvancer(player, container.queueRepository, lifecycleScope) {
                // The timer is consumed first and unconditionally, never short-circuited by the
                // setting: an armed timer has to be disarmed by the episode it was set for, or
                // turning auto-advance off would leave it armed for every episode after this one.
                val stoppingForSleep = container.sleepTimer.consumeEndOfEpisode()
                container.settings.autoAdvance && !stoppingForSleep
            }
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = mediaSession

    /**
     * Where tapping the notification - or the car's "open app" - lands.
     *
     * Without a session activity the notification has no content intent at all, so tapping it did
     * nothing: the transport buttons worked while the notification itself was inert. Media3's
     * default notification provider takes the content intent from here and nowhere else.
     *
     * Aimed at Now Playing rather than wherever the app was last left, because the notification is
     * *about* the thing playing - being dropped onto a search screen after tapping it would be a
     * non-sequitur.
     */
    private fun nowPlayingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        /* requestCode = */ 0,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true),
        // Immutable because nothing outside the app has any business rewriting it, and mutability
        // must be stated explicitly from Android 12 onwards.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun seedLastPlayedEpisode(episodeRepository: EpisodeRepository) {
        lifecycleScope.launch { SessionSeeder(episodeRepository).seed(player) }
    }

    /**
     * Without this, the system notification and Android Auto fall back to their own default
     * button set - generic rewind/skip-to-previous glyphs with no indication of how far they
     * seek, since there's no real "previous track" (every episode plays standalone; see
     * [AutoAdvancer]).
     *
     * Media3 ships baked icons for exactly 5, 10, 15 and 30 seconds, which is the reason
     * [SkipAmount] offers three fixed values rather than a slider: every settable amount has a
     * glyph that states it truthfully, here and in the app.
     */
    private fun skipButtonPreferences(back: SkipAmount, forward: SkipAmount): List<CommandButton> = listOf(
        CommandButton.Builder(back.backIcon())
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setDisplayName("Back ${back.seconds} seconds")
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(forward.forwardIcon())
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setDisplayName("Forward ${forward.seconds} seconds")
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
    )

    private fun SkipAmount.backIcon(): Int = when (this) {
        SkipAmount.FIVE -> CommandButton.ICON_SKIP_BACK_5
        SkipAmount.FIFTEEN -> CommandButton.ICON_SKIP_BACK_15
        SkipAmount.THIRTY -> CommandButton.ICON_SKIP_BACK_30
    }

    private fun SkipAmount.forwardIcon(): Int = when (this) {
        SkipAmount.FIVE -> CommandButton.ICON_SKIP_FORWARD_5
        SkipAmount.FIFTEEN -> CommandButton.ICON_SKIP_FORWARD_15
        SkipAmount.THIRTY -> CommandButton.ICON_SKIP_FORWARD_30
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!player.isPlaying) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        progressWriter.flushBlocking()
        mediaSession.release()
        // Releases the wrapped ExoPlayer too, and detaches the listener the wrapper holds on it.
        sessionPlayer.release()
        super.onDestroy()
    }
}
