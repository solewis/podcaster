package com.solewis.podcaster.player

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.solewis.podcaster.data.settings.SettingsStore

/**
 * Records the playback speed whenever it changes, so it survives the process being killed. Paired
 * with [PlaybackService] reading the saved value back when it builds the player.
 *
 * Attached to the player rather than to the one menu that currently changes speed: the session is
 * driven by things that are not this app's UI - Android Auto, a media button, a future
 * per-show override - and a preference that quietly stops persisting the moment a second caller
 * appears is exactly the bug this class exists to fix, one layer down.
 */
class SpeedPersister(private val settings: SettingsStore) : Player.Listener {

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        settings.speed = playbackParameters.speed
    }
}
