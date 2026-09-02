package com.solewis.podcaster.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

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
 * episode reads "<date> · 20m left" with a bar, a *finished* one reads "<date> · Finished" with no
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
            label = listOfNotNull(date, FINISHED_LABEL).joinToString(" · "),
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

/**
 * The word the finished state is spelled with, and the anchor [EpisodeMetaLine] looks for when
 * placing the tick. A constant so the two cannot drift apart into a tick that never appears.
 *
 * "Finished" rather than "Played": the rows also carry a *play* button, and a line reading "Played"
 * next to a control labelled "Play" invited exactly the wrong reading.
 */
const val FINISHED_LABEL = "Finished"

/**
 * The metadata line under an episode title, with the finished tick sitting inline right after the
 * word rather than off on its own.
 *
 * It used to be a separate icon in the trailing controls column, under the play button and the
 * overflow menu, which left it floating between the two with nothing to attach it to - it read as
 * a third control you could press rather than as a statement about the episode. Inline, it is
 * punctuation on the sentence that already says "Finished".
 *
 * [androidx.compose.ui.text.InlineTextContent] rather than a `Row` of text-then-icon, because the
 * tick has to follow the *word* and the word is not always last: a downloaded episode reads
 * "Finished · Downloaded", and a row wide enough to wrap has to break around the tick like any
 * other glyph.
 */
@Composable
fun EpisodeMetaLine(
    label: String,
    isPlayed: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    if (label.isEmpty()) return
    val style = MaterialTheme.typography.bodySmall
    val at = label.indexOf(FINISHED_LABEL).takeIf { isPlayed && it >= 0 }
    if (at == null) {
        Text(label, style = style, color = color, modifier = modifier)
        return
    }

    val after = at + FINISHED_LABEL.length
    val text = buildAnnotatedString {
        append(label.substring(0, after))
        appendInlineContent(TICK, "\u2713")
        append(label.substring(after))
    }
    Text(
        text,
        style = style,
        color = color,
        modifier = modifier,
        inlineContent = mapOf(
            TICK to InlineTextContent(
                // Sized in em so the tick scales with the text rather than being pinned to a dp
                // that only looks right at one font scale. Wider than it is tall to carry its own
                // leading gap, since an inline placeholder has no margin of its own.
                Placeholder(
                    width = 1.5.em,
                    height = 1.1.em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    // Already stated by the word it follows, so naming it again would make a
                    // screen reader say "Finished, played".
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp).size(13.dp)
                )
            }
        )
    )
}

private const val TICK = "finishedTick"
