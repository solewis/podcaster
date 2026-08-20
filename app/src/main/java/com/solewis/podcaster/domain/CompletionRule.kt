package com.solewis.podcaster.domain

/**
 * Decides whether an episode counts as finished. A fixed end-of-episode threshold (rather than
 * requiring the exact final millisecond) absorbs outros/credits and the player's own rounding,
 * without falsely completing an episode the user paused two minutes early.
 */
object CompletionRule {

    private const val DEFAULT_THRESHOLD_MILLIS = 20_000L

    fun isComplete(
        positionMillis: Long,
        durationMillis: Long?,
        thresholdMillis: Long = DEFAULT_THRESHOLD_MILLIS
    ): Boolean {
        if (durationMillis == null || durationMillis <= 0) return false
        return positionMillis >= durationMillis - thresholdMillis
    }
}
