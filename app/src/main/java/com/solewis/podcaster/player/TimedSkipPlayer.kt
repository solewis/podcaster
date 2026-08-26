package com.solewis.podcaster.player

import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import com.google.common.util.concurrent.ListenableFuture

/**
 * Makes the car's steering-wheel `<<` / `>>` buttons perform timed skips, the way Spotify treats
 * them for podcasts. Those buttons reach the app as `KEYCODE_MEDIA_PREVIOUS`/`_NEXT` - there
 * is no "skip 15 seconds" media key for a head unit to send - and `MediaSessionImpl` dispatches them
 * to `Player.seekToPrevious()`/`seekToNext()` after checking the matching command is available.
 *
 * Every episode plays as a single-item playlist (continuing to the next episode is [AutoAdvancer]'s
 * job, not the player's), and ExoPlayer derives seek-command availability purely from playlist
 * adjacency (`Util.getAvailableCommands`). That broke the two buttons in two *different* ways:
 *
 *  - [Player.COMMAND_SEEK_TO_NEXT] requires a next item (or a live, dynamic one), so it was
 *    withheld and the `>>` press was dropped on the availability check - nothing at all.
 *  - [Player.COMMAND_SEEK_TO_PREVIOUS] *was* granted, since a non-live seekable item qualifies for
 *    it even with nothing before it. But with no previous item, `BasePlayer.seekToPrevious()` falls
 *    through to seeking the current item to zero, so `<<` restarted the episode. Reaching for it
 *    blind at forty minutes in, expecting to back up a sentence, is a genuinely bad surprise.
 *
 * Hence declaring both commands available *and* rerouting them, which has to happen on the player:
 * every route into the session re-checks availability and then calls straight through, whether the
 * press arrives as a media key or as `onSkipToNext()` from a legacy controller (which is how
 * Android Auto connects).
 *
 * Note this deliberately does not try to fix the *on-screen* Auto/notification buttons, which are
 * a separate mechanism and already correct: because [PlaybackService] fills `SLOT_BACK`/`SLOT_FORWARD`
 * with its own 15-second `CommandButton`s, `MediaSessionLegacyStub` strips `ACTION_SKIP_TO_PREVIOUS`
 * and `ACTION_SKIP_TO_NEXT` from the legacy `PlaybackStateCompat` - by design, since those slots are
 * taken. Media keys are not gated on that action set, so the wheel is fixed here without disturbing
 * the button layout.
 *
 * [ForwardingSimpleBasePlayer] rather than `ForwardingPlayer`, on Media3's own advice: a
 * `ForwardingPlayer` overriding `getAvailableCommands()` would still forward the *wrapped* player's
 * `Commands` verbatim through `onAvailableCommandsChanged`, leaving the session with one command set
 * on query and a different one on the event. Here the whole state is rebuilt from [getState], so the
 * two cannot disagree.
 *
 * This is also where the *size* of a skip is decided, for every route into the session at once. It
 * has to be here rather than on the `ExoPlayer`, because `ExoPlayer`'s seek increments are fixed
 * when it is built ([androidx.media3.exoplayer.ExoPlayer.Builder.setSeekBackIncrementMs]) and cannot
 * be changed afterwards - so a configurable amount would otherwise mean tearing down and rebuilding
 * the player mid-listen. Read through a lambda on each use, so changing the setting takes effect on
 * the very next press.
 */
class TimedSkipPlayer(
    player: Player,
    private val skipBackMillis: () -> Long = { DEFAULT_SKIP_MILLIS },
    private val skipForwardMillis: () -> Long = { DEFAULT_SKIP_MILLIS }
) : ForwardingSimpleBasePlayer(player) {

    override fun getState(): State {
        val state = super.getState()
        return state.buildUpon()
            .setAvailableCommands(
                state.availableCommands.buildUpon()
                    .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS)
                    .build()
            )
            // Overriding the wrapped player's build-time increments, so anything that *displays* the
            // amount - the notification, the car - agrees with what a press actually does.
            .setSeekBackIncrementMs(skipBackMillis())
            .setSeekForwardIncrementMs(skipForwardMillis())
            .build()
    }

    /**
     * Every skip becomes an absolute seek within the current item.
     *
     * The reason is a trap worth naming: [ForwardingSimpleBasePlayer.handleSeek] implements
     * `COMMAND_SEEK_BACK` as `player.seekBack()` on the *wrapped* player, which uses that player's
     * own build-time increment and ignores the one reported by [getState] entirely. Delegating with
     * a substituted command - what this used to do - would therefore show one number and seek a
     * different one, silently, the moment the two stopped both being 15 seconds.
     *
     * Computing the target here also collapses the next/previous rerouting into the same path, so
     * there is exactly one definition of how far a skip goes.
     */
    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> = when (seekCommand) {
        Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_NEXT ->
            seekBy(mediaItemIndex, skipForwardMillis())
        Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_TO_PREVIOUS ->
            seekBy(mediaItemIndex, -skipBackMillis())
        else -> super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    private fun seekBy(mediaItemIndex: Int, offsetMillis: Long): ListenableFuture<*> {
        val duration = contentDuration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        val target = (contentPosition + offsetMillis).coerceIn(0L, duration)
        return super.handleSeek(mediaItemIndex, target, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    }

    private companion object {
        /** Only for a caller that has no setting to offer - the default the app itself starts at. */
        const val DEFAULT_SKIP_MILLIS = 15_000L
    }
}
