package com.solewis.podcaster.player

import com.solewis.podcaster.data.net.Connectivity
import com.solewis.podcaster.data.repo.Downloads
import com.solewis.podcaster.data.repo.PlayableEpisode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The single way an episode gets started, and the single owner of the gap between tapping play and
 * hearing something.
 *
 * Six screens used to call `playback.play` directly, which left two things scattered or missing.
 * The wait between a tap and audio - controller connection, then buffering - was shown on the Home
 * feed and nowhere else, so a tap on a show's episode list looked like nothing had happened. And
 * with no connection, nothing checked: playback would sit there retrying inside ExoPlayer with no
 * indication, which reads as a hang rather than as a failure.
 *
 * App-scoped, so [pendingEpisodeId] follows you between screens - tap play on the Home feed, open
 * the show, and the spinner is still on the right row rather than resetting.
 */
class PlaybackStarter(
    private val playback: Playback,
    private val downloads: Downloads,
    private val connectivity: Connectivity,
    scope: CoroutineScope
) {

    private val _pendingEpisodeId = MutableStateFlow<String?>(null)

    /** The episode that has been asked for but is not yet audible - what a spinner hangs off. */
    val pendingEpisodeId: StateFlow<String?> = _pendingEpisodeId.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Things worth telling the user about a start that did not happen. Shown once, anywhere. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        // Genuinely clears the pending tap rather than merely hiding it while `isPlaying` holds.
        // Masking it downstream instead looked identical at first - until you paused, which
        // un-masked the stale id and put the spinner back on an episode that had long since
        // started. Also clears when some *other* episode takes over, so a tap that never produced
        // sound cannot leave a spinner stuck forever.
        scope.launch {
            playback.state.collect { state ->
                val pending = _pendingEpisodeId.value ?: return@collect
                if (state.episodeId == pending && state.isPlaying) {
                    _pendingEpisodeId.value = null
                } else if (state.episodeId != null && state.episodeId != pending) {
                    _pendingEpisodeId.value = null
                }
            }
        }
    }

    /**
     * Starts [episode], unless there is no way for it to produce sound.
     *
     * Refusing up front rather than letting it fail slowly is the whole point: ExoPlayer would
     * otherwise spend its retry budget on a connection that is not there, which from the outside is
     * indistinguishable from the app having frozen. A downloaded episode needs no network at all,
     * so it is checked before giving up.
     */
    suspend fun start(episode: PlayableEpisode) {
        if (!connectivity.isOnline() && !downloads.isDownloaded(episode.episodeId)) {
            _messages.tryEmit(NO_CONNECTION_MESSAGE)
            _pendingEpisodeId.value = null
            return
        }
        _pendingEpisodeId.value = episode.episodeId
        playback.play(episode)
    }

    companion object {
        /**
         * Says which of the two things is missing, because the fix differs: connect to something,
         * or download it while you still can.
         */
        const val NO_CONNECTION_MESSAGE = "No connection - this episode isn't downloaded"
    }
}
