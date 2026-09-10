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

    /**
     * The duration the player has reported for each episode it has loaded, so a write can be given
     * the duration of the episode it is *about*.
     *
     * `player.duration` only ever describes whatever is loaded right now, and that is the wrong
     * episode at exactly the moment it matters. Switching episodes writes the outgoing one's final
     * position, and by then the player has moved on - measured on device, the discontinuity arrives
     * with `player.duration == TIME_UNSET` because the incoming episode has not been prepared yet:
     *
     *     DISCONTINUITY reason=4 oldId=A oldPos=600009 newId=B newPos=0 duration=TIME_UNSET
     *
     * A null duration makes [CompletionRule] answer "not complete", so leaving a finished episode
     * to play another cleared its played flag and left it showing a full progress bar instead of
     * "Finished". A *shorter* incoming episode is the same bug pointing the other way - it would
     * falsely complete the one being left. One entry per episode played, so this stays tiny.
     */
    private val reportedDurations = mutableMapOf<String, Long>()

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
            enqueue(mediaId, newPosition.positionMs)
            return
        }
        enqueue(mediaId, oldPosition.positionMs)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) rememberDuration()
        if (playbackState == Player.STATE_ENDED) {
            val mediaId = player.currentMediaItem?.mediaId ?: return
            // A position guaranteed to satisfy CompletionRule regardless of exactly what
            // player.currentPosition reports at the ENDED boundary.
            enqueue(mediaId, durationFor(mediaId) ?: Long.MAX_VALUE)
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_TRACKS_CHANGED)) {
            val mediaId = player.currentMediaItem?.mediaId ?: return
            val duration = durationMillisOrNull() ?: return
            reportedDurations[mediaId] = duration
            scope.launch { episodeRepository.backfillDuration(mediaId, duration) }
        }
    }

    private fun rememberDuration() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        durationMillisOrNull()?.let { reportedDurations[mediaId] = it }
    }

    private fun enqueueCurrentPosition() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        enqueue(mediaId, player.currentPosition)
    }

    private fun enqueue(episodeId: String, positionMillis: Long) {
        updates.trySend(Update(episodeId, positionMillis, durationFor(episodeId)))
    }

    /**
     * The duration for [episodeId] specifically - the live one only when it really is the loaded
     * episode, and the remembered one otherwise. See [reportedDurations].
     */
    private fun durationFor(episodeId: String): Long? {
        if (episodeId == player.currentMediaItem?.mediaId) {
            durationMillisOrNull()?.let { return it }
        }
        return reportedDurations[episodeId]
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
        val update = Update(mediaId, player.currentPosition, durationFor(mediaId))
        runBlocking { write(update) }
    }

    private companion object {
        const val TICK_INTERVAL_MILLIS = 5_000L
    }
}
