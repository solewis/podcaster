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
 * Deliberately does **not** prepare. [androidx.media3.common.util.Util.handlePlayButtonAction] -
 * which is what a session routes a play command through - prepares an idle player first. So the
 * metadata costs nothing and not a byte is fetched until somebody actually presses play.
 */
class SessionSeeder(private val episodeRepository: EpisodeRepository) {

    suspend fun seed(player: Player) {
        val episode = episodeRepository.getLastPlayed() ?: return
        // Never over the top of a live playlist: the service outlives individual controllers, so by
        // the time the database answers something may already be loaded or playing.
        if (player.currentMediaItem != null) return
        player.setMediaItem(MediaItemMapper.toMediaItem(episode), episode.startPositionMillis)
    }
}
