package com.solewis.podcaster.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.domain.CompletionRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The single place playback progress gets written to Room. Two cadences funnel into one
 * conflated channel drained by a lone coroutine, so a burst of seeks collapses into one write
 * rather than many (~12 tiny writes per minute from the ticker alone - negligible):
 *  - a 5s ticker while playing
 *  - immediate writes on pause, seek/transition, and playback ending
 *
 * Also backfills [com.solewis.podcaster.data.db.entity.EpisodeEntity.durationMillis] once the
 * player reports a real one - many feeds have missing or wrong `<itunes:duration>` values,
 * verified against real captured feeds before this was built.
 */
class ProgressWriter(
    private val player: ExoPlayer,
    private val episodeRepository: EpisodeRepository,
    /** Must be dispatched on the player's application thread - the ticker reads player state
     * directly, and ExoPlayer throws when touched from anywhere else. */
    private val scope: CoroutineScope
) : Player.Listener {

    private data class Update(val episodeId: String, val positionMillis: Long, val durationMillis: Long?)

    private val updates = Channel<Update>(Channel.CONFLATED)

    init {
        scope.launch { for (update in updates) write(update) }
        scope.launch { tick() }
    }

    private suspend fun tick() {
        while (true) {
            delay(TICK_INTERVAL_MILLIS)
            if (player.isPlaying) {
                enqueueCurrentPosition()
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) enqueueCurrentPosition()
    }

    /**
     * ⚠️ By the time this fires, `player.currentPosition` already refers to the *new* item -
     * reading it here would silently record the wrong episode's position, the kind of bug that
     * looks like resume points "randomly" resetting. [oldPosition] is for the outgoing item.
     *
     * Which of the two positions to record depends on whether the episode changed, and getting
     * that wrong is what made "mark as finished" appear not to work. A discontinuity fires for a
     * plain seek *within* one episode too (`DISCONTINUITY_REASON_SEEK`, both `mediaItem`s the
     * same), and recording [oldPosition] there writes the position the listener seeked away
     * from - with `isPlayed = false`, since a mid-episode position is not complete. [PlayedMarker]
     * marks the episode played and then seeks to the end, so that write landed immediately
     * afterwards and reverted the mark; the row went on showing a progress bar. Independently of
     * marking, every seek was recording the wrong position until the next 5s tick corrected it -
     * so a seek followed by the app being killed lost the seek.
     */
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        val mediaId = oldPosition.mediaItem?.mediaId ?: return
        if (mediaId == newPosition.mediaItem?.mediaId) {
            enqueue(mediaId, newPosition.positionMs, durationMillisOrNull())
            return
        }
        enqueue(mediaId, oldPosition.positionMs, durationMillisOrNull())
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            val mediaId = player.currentMediaItem?.mediaId ?: return
            // A position guaranteed to satisfy CompletionRule regardless of exactly what
            // player.currentPosition reports at the ENDED boundary.
            val duration = durationMillisOrNull()
            enqueue(mediaId, duration ?: Long.MAX_VALUE, duration)
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_TRACKS_CHANGED)) {
            val mediaId = player.currentMediaItem?.mediaId ?: return
            val duration = durationMillisOrNull() ?: return
            scope.launch { episodeRepository.backfillDuration(mediaId, duration) }
        }
    }

    private fun enqueueCurrentPosition() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        enqueue(mediaId, player.currentPosition, durationMillisOrNull())
    }

    private fun enqueue(episodeId: String, positionMillis: Long, durationMillis: Long?) {
        updates.trySend(Update(episodeId, positionMillis, durationMillis))
    }

    private fun durationMillisOrNull(): Long? = player.duration.takeIf { it != C.TIME_UNSET }

    private suspend fun write(update: Update) {
        if (CompletionRule.isComplete(update.positionMillis, update.durationMillis)) {
            episodeRepository.setProgress(update.episodeId, positionMillis = 0L, isPlayed = true)
        } else {
            episodeRepository.setProgress(update.episodeId, update.positionMillis, isPlayed = false)
        }
    }

    /** Best-effort final flush - called from `PlaybackService.onDestroy`, which is not suspending. */
    fun flushBlocking() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val update = Update(mediaId, player.currentPosition, durationMillisOrNull())
        runBlocking { write(update) }
    }

    private companion object {
        const val TICK_INTERVAL_MILLIS = 5_000L
    }
}
