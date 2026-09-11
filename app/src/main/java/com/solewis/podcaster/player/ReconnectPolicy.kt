package com.solewis.podcaster.player

/**
 * How long to wait between reconnect attempts, and when to stop trying.
 *
 * A plain class rather than folded into [PlaybackErrorRetrier], so the *schedule* has a test that
 * runs in milliseconds against virtual time, independent of standing up a real player or a real
 * network failure to exercise it - see `ReconnectPolicyTest`. A real class rather than an object
 * for the same reason: a test proving "gives up eventually" cannot afford to wait out the real
 * three minutes, and needs a policy built with a much shorter ceiling instead.
 *
 * The shape - a handful of short waits, then a steady interval, then give up - is deliberately not
 * "retry forever": a silent infinite spinner is its own failure mode. Spotify's mobile app behaves
 * the same way in practice - it keeps trying quietly for a while and then surfaces an explicit
 * "no connection" state rather than spinning indefinitely.
 */
class ReconnectPolicy(
    /** Quick at first - a lot of real drops (a tunnel, a brief dead zone) clear within seconds. */
    private val earlyBackoffMillis: List<Long> = listOf(3_000L, 6_000L, 12_000L, 24_000L),
    /** Once the quick attempts are exhausted, poll unhurriedly rather than spin the radio. */
    private val steadyStateMillis: Long = 30_000L,
    /**
     * Three minutes by default. Long enough to survive a real dead zone or a slow reconnect,
     * short enough that "it just silently stopped" never lasts an entire commute unremarked.
     */
    val giveUpAfterMillis: Long = 3 * 60_000L
) {
    /** The delay before the (1-indexed) attempt numbered [attempt]. */
    fun delayBeforeAttempt(attempt: Int): Long =
        earlyBackoffMillis.getOrElse(attempt - 1) { steadyStateMillis }

    /** Whether it is still worth trying, given [elapsedMillis] since the error first occurred. */
    fun shouldKeepTrying(elapsedMillis: Long): Boolean = elapsedMillis < giveUpAfterMillis
}
