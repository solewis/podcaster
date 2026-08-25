package com.solewis.podcaster.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * The thin "how far into this episode am I" bar, shown anywhere a started-but-unfinished episode
 * appears (Home feed, show episode list, episode detail). Deliberately shared rather than
 * re-inlined per screen so an episode reads the same everywhere you meet it.
 */
@Composable
fun EpisodeProgressBar(positionMillis: Long, durationMillis: Long, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f) },
        // Material 3 draws a "stop indicator" dot at the track end and a gap before it by
        // default. That reads as a marker you could act on, which this isn't - it's a plain
        // how-far-through bar - so both are switched off for one continuous fill.
        drawStopIndicator = {},
        gapSize = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
    )
}

/**
 * The secondary line under an episode title, and whether that episode still has a progress bar
 * to draw. Centralised because the rule is easy to get subtly wrong per-screen: a *started*
 * episode reads "<date> · 20m left" with a bar, a *finished* one reads "<date> · Played" with no
 * bar, and an untouched one just reads "<date> · 51m".
 *
 * [livePositionMillis]/[liveDurationMillis] come from the player for the episode currently making
 * sound - they tick every 500ms, versus the ~5s granularity of the persisted position, so the row
 * you're actually listening to moves smoothly instead of in visible jumps.
 */
data class EpisodeProgressUi(val label: String, val positionMillis: Long?, val durationMillis: Long?) {
    val showBar: Boolean get() = positionMillis != null && durationMillis != null
}

fun episodeProgressUi(
    pubDateMillis: Long?,
    durationMillis: Long?,
    positionMillis: Long,
    isPlayed: Boolean,
    livePositionMillis: Long? = null,
    liveDurationMillis: Long? = null
): EpisodeProgressUi {
    val date = formatEpisodeDate(pubDateMillis)
    val position = livePositionMillis ?: positionMillis
    val duration = liveDurationMillis ?: durationMillis

    return when {
        isPlayed -> EpisodeProgressUi(
            label = listOfNotNull(date, "Played").joinToString(" · "),
            positionMillis = null,
            durationMillis = null
        )
        position > 0 && duration != null -> EpisodeProgressUi(
            label = listOfNotNull(date, formatRemaining(position, duration)).joinToString(" · "),
            positionMillis = position,
            durationMillis = duration
        )
        else -> EpisodeProgressUi(
            label = listOfNotNull(date, formatDuration(durationMillis)).joinToString(" · "),
            positionMillis = null,
            durationMillis = null
        )
    }
}
