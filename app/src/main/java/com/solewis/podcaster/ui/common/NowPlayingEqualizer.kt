package com.solewis.podcaster.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin

/**
 * The three little bars marking the episode that is loaded in the player, wherever that episode
 * appears in a list.
 *
 * It answers a question the row could not previously answer at all. The play/pause button says
 * whether *something* is playing, but on a paused episode it draws a play arrow - identical to
 * every other row - so the episode you were half way through was indistinguishable from one you
 * had never opened. The bars mark the row; the button still says what it will do to it.
 *
 * Animating means playing and still means paused, which is the same distinction the button draws
 * and worth stating twice: a glance at a list is usually looking for "where was I", not "is sound
 * coming out".
 */
@Composable
fun NowPlayingEqualizer(isPlaying: Boolean, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val description = if (isPlaying) "Now playing" else "Paused here"

    // One animation driving all three bars, rather than three animations with staggered delays.
    // Each bar reads the same phase through its own offset, which is a little arithmetic in the
    // draw pass instead of three more Animatables per row.
    val phase: State<Float> = if (isPlaying) {
        rememberInfiniteTransition(label = "equalizer").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(CYCLE_MILLIS, easing = LinearEasing)),
            label = "equalizerPhase"
        )
    } else {
        // Parked, so a paused row costs nothing at all - no transition, no frame callbacks.
        remember { mutableFloatStateOf(0f) }
    }

    Canvas(
        modifier = modifier
            .size(width = GlyphWidth, height = GlyphHeight)
            .semantics { contentDescription = description }
    ) {
        // `phase` is read here, inside the draw lambda, and deliberately not in the composable
        // body above: an animation read during composition recomposes this on every frame, and
        // read during draw it only redraws. The row around it never recomposes either way, which
        // is the whole reason this is a Canvas and not three animated Boxes.
        drawBars(phase.value, color, isPlaying)
    }
}

private fun DrawScope.drawBars(phase: Float, color: Color, isPlaying: Boolean) {
    val barWidth = size.width / (BAR_COUNT * 2 - 1)
    repeat(BAR_COUNT) { index ->
        val fraction = if (isPlaying) {
            // abs(sin) rather than sin, so a bar bounces off the bottom instead of inverting
            // through it, and every bar is somewhere different in the cycle.
            MIN_FRACTION + (1f - MIN_FRACTION) *
                abs(sin((phase + index * PHASE_STEP) * 2f * Math.PI.toFloat()))
        } else {
            // A settled shape rather than all three equal, which read as a loading placeholder.
            PausedFractions[index]
        }
        val barHeight = size.height * fraction
        drawRect(
            color = color,
            // Grown upward from the baseline: bars that grow from the middle read as a waveform,
            // which is a different thing from a level meter.
            topLeft = Offset(x = index * barWidth * 2f, y = size.height - barHeight),
            size = Size(width = barWidth, height = barHeight)
        )
    }
}

private const val BAR_COUNT = 3

/** How far apart the bars sit in the cycle. Not 1/3, which marches them in step. */
private const val PHASE_STEP = 0.22f

/** Bars never collapse to nothing - three gaps reads as broken rather than quiet. */
private const val MIN_FRACTION = 0.3f

private val PausedFractions = floatArrayOf(0.5f, 1f, 0.7f)

private const val CYCLE_MILLIS = 900

/** Sized against the `bodySmall` line it sits on, so it reads as punctuation on that line. */
private val GlyphHeight = 10.dp
private val GlyphWidth = 10.dp
