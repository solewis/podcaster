package com.solewis.podcaster.player

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import com.google.common.util.concurrent.ListenableFuture

/**
 * Makes the car's steering-wheel `<<` / `>>` buttons perform the 15-second skips, the way Spotify
 * treats them for podcasts. Those buttons reach the app as `KEYCODE_MEDIA_PREVIOUS`/`_NEXT` - there
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
 */
class TimedSkipPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {

    override fun getState(): State {
        val state = super.getState()
        return state.buildUpon()
            .setAvailableCommands(
                state.availableCommands.buildUpon()
                    .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS)
                    .build()
            )
            .build()
    }

    // Delegating to super with a substituted command (rather than calling seekForward()/seekBack()
    // on the wrapped player directly) keeps SimpleBasePlayer's own bookkeeping - the placeholder
    // state it publishes while the seek is in flight - consistent with what actually happened.
    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> = when (seekCommand) {
        Player.COMMAND_SEEK_TO_NEXT ->
            super.handleSeek(mediaItemIndex, positionMs, Player.COMMAND_SEEK_FORWARD)
        Player.COMMAND_SEEK_TO_PREVIOUS ->
            super.handleSeek(mediaItemIndex, positionMs, Player.COMMAND_SEEK_BACK)
        else -> super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }
}
