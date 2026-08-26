package com.solewis.podcaster.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SleepTimerState {

    data object Off : SleepTimerState

    /** [remainingMillis] counts down, so the screen can show it without a clock of its own. */
    data class Running(val remainingMillis: Long) : SleepTimerState

    /**
     * Armed, but with no deadline: playback stops when the current episode finishes. Distinct from
     * [Running] because there is nothing to count down - the answer is however long is left.
     */
    data object EndOfEpisode : SleepTimerState
}

/**
 * Stops playback after a set time, or at the end of the current episode.
 *
 * App-scoped rather than tied to a screen, because the point of it is to keep running once the
 * phone is face-down and the app is in the background - a timer owned by a ViewModel would be
 * cancelled the moment Now Playing left the composition. It pauses through [Playback] rather than
 * reaching for the `ExoPlayer` directly, which keeps it testable and means the pause travels the
 * same route as one from the notification.
 *
 * The end-of-episode mode is not implemented by watching for the episode to end. It is implemented
 * by *suppressing auto-advance* once - see [consumeEndOfEpisode]. An episode reaching its end
 * already leaves the player stopped and not playing; the only thing that would carry on is
 * [AutoAdvancer] starting the next one, so declining that is the whole behaviour, and it avoids two
 * listeners racing over the same `STATE_ENDED`.
 */
class SleepTimer(
    private val playback: Playback,
    private val scope: CoroutineScope,
    /**
     * Injectable so a test can drive it from virtual time. Remaining time is derived from this
     * rather than by counting [delay]s, so a coroutine that wakes late reports the truth instead of
     * drifting.
     */
    private val now: () -> Long = System::currentTimeMillis
) {

    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var countdown: Job? = null

    fun start(durationMillis: Long) {
        countdown?.cancel()
        val endsAt = now() + durationMillis
        _state.value = SleepTimerState.Running(durationMillis)
        countdown = scope.launch {
            while (true) {
                val remaining = endsAt - now()
                if (remaining <= 0) break
                _state.value = SleepTimerState.Running(remaining)
                delay(minOf(TICK_MILLIS, remaining))
            }
            playback.pause()
            _state.value = SleepTimerState.Off
        }
    }

    fun startEndOfEpisode() {
        countdown?.cancel()
        countdown = null
        _state.value = SleepTimerState.EndOfEpisode
    }

    /**
     * Adds time to a running timer, or starts one if nothing is set - what the button offers when
     * the episode turns out to be more interesting than expected.
     *
     * Deliberately does nothing in [SleepTimerState.EndOfEpisode]: there is no deadline to extend,
     * and silently converting it into a countdown would throw away what was actually asked for.
     */
    fun extend(byMillis: Long) {
        when (val current = _state.value) {
            is SleepTimerState.Running -> start(current.remainingMillis + byMillis)
            SleepTimerState.Off -> start(byMillis)
            SleepTimerState.EndOfEpisode -> Unit
        }
    }

    fun cancel() {
        countdown?.cancel()
        countdown = null
        _state.value = SleepTimerState.Off
    }

    /**
     * Asked by [AutoAdvancer] when an episode ends: true means stop here rather than starting the
     * next one, and disarms the timer so the following episode is unaffected.
     *
     * A question with a side effect, which is unusual enough to name. The alternative - the timer
     * listening for `STATE_ENDED` itself - puts two listeners on the same event with the outcome
     * depending on which was registered first.
     */
    fun consumeEndOfEpisode(): Boolean {
        if (_state.value != SleepTimerState.EndOfEpisode) return false
        _state.value = SleepTimerState.Off
        return true
    }

    private companion object {
        /** Once a second: the screen shows minutes and seconds, so anything finer is wasted work. */
        const val TICK_MILLIS = 1_000L
    }
}
