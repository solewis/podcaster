package com.solewis.podcaster.player

import androidx.media3.common.Player
import com.solewis.podcaster.data.repo.EpisodeRepository

/**
 * Puts the last-played episode into the player as soon as the playback service exists, so anything
 * reading the session has something to show.
 *
 * This is what was missing in the car. The app's startup restore seeds only
 * [PlayerConnection]'s UI state - deliberately, so that opening the app merely to browse costs
 * neither a service nor a buffer. Android Auto does not read that; it reads the session. So once
 * the car had been off long enough for the service to die, reconnecting built a session over an
 * empty player: Auto had no metadata and its widget fell back to "tap to open", while the phone,
 * reading its own restored state, still showed the episode. Pressing play on the phone loaded the
 * item and Auto caught up instantly, which is the signature of exactly that gap.
 *
 * It also prepares, which an earlier version deliberately avoided in order to fetch nothing until
 * play was pressed. That was wrong, and wrong in a way only a car reveals: an unprepared player is
 * `STATE_IDLE` and therefore has no timeline, so `COMMAND_SEEK_BACK` and `COMMAND_SEEK_FORWARD` are
 * *unavailable* - and Media3 disables a `CommandButton` whose player command is unavailable. The
 * session looked controllable and was not. Verified on a device: idle reports `seekBack=false,
 * seekFwd=false`, which is precisely a car whose skip buttons do nothing.
 *
 * So the buffering is the price of a session that actually works. It is bounded by the load control
 * and only happens when a controller connects, which is to say when something already wants to play.
 */
class SessionSeeder(private val episodeRepository: EpisodeRepository) {

    suspend fun seed(player: Player) {
        val episode = episodeRepository.getLastPlayed() ?: return
        // Never over the top of a live playlist: the service outlives individual controllers, so by
        // the time the database answers something may already be loaded or playing.
        if (player.currentMediaItem != null) return
        player.setMediaItem(MediaItemMapper.toMediaItem(episode), episode.startPositionMillis)
        player.prepare()
    }
}
