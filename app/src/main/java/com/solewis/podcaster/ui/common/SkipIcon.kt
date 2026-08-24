package com.solewis.podcaster.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The 15-second skip control, drawn rather than composed from Material's icon set.
 *
 * Material only ships Replay/Forward glyphs baked for 5/10/30 seconds, so earlier versions of
 * this overlaid a "15" on the generic `Replay` glyph. That never sat right: the glyph's ring is
 * sized and positioned for an empty centre, so the numerals kept colliding with the stroke, and
 * shrinking the text far enough to clear it left the digits unreadably small. Drawing the arc
 * ourselves means the ring's radius, stroke weight and the gap the arrowhead sits in are all
 * chosen around the number instead of fighting it.
 *
 * Proportions follow the usual podcast-player treatment (checked against Spotify's): a nearly
 * closed ring with a single arrowhead at the top, and numerals large enough to read at a glance
 * on a car screen or at arm's length.
 */
@Composable
fun SkipIcon(seconds: Int, forward: Boolean, contentDescription: String, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    val textMeasurer = rememberTextMeasurer()
    val label = seconds.toString()

    Canvas(modifier = modifier.semantics { this.contentDescription = contentDescription }) {
        val extent = size.minDimension
        val radius = extent * RADIUS_FRACTION
        val strokeWidth = extent * STROKE_FRACTION
        val middle = Offset(size.width / 2f, size.height / 2f)

        // Mirrored for the forward variant so the arrow reads clockwise; drawn identically
        // otherwise, which keeps the two buttons exactly symmetrical.
        scale(scaleX = if (forward) -1f else 1f, scaleY = 1f, pivot = middle) {
            drawArc(
                color = color,
                startAngle = ARC_START_DEGREES,
                sweepAngle = ARC_SWEEP_DEGREES,
                useCenter = false,
                topLeft = Offset(middle.x - radius, middle.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = strokeWidth)
            )

            // Arrowhead capping the arc's counter-clockwise end, built from the tangent and
            // radial vectors at that exact angle so it sits *on* the curve rather than floating
            // above it - the giveaway that an earlier version was positioning it by eye.
            val endRadians = Math.toRadians((ARC_START_DEGREES + ARC_SWEEP_DEGREES).toDouble())
            val radial = Offset(cos(endRadians).toFloat(), sin(endRadians).toFloat())
            val tangent = Offset(sin(endRadians).toFloat(), -cos(endRadians).toFloat())
            val onArc = middle + radial * radius
            val tip = onArc + tangent * (strokeWidth * ARROW_LENGTH_FACTOR)
            val halfWidth = radial * (strokeWidth * ARROW_HALF_WIDTH_FACTOR)

            drawPath(
                path = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo((onArc + halfWidth).x, (onArc + halfWidth).y)
                    lineTo((onArc - halfWidth).x, (onArc - halfWidth).y)
                    close()
                },
                color = color
            )
        }

        val measured = textMeasurer.measure(
            text = label,
            style = TextStyle(
                color = color,
                fontSize = (extent * TEXT_FRACTION).toSp(),
                fontWeight = FontWeight.Bold
            )
        )
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(
                x = middle.x - measured.size.width / 2f,
                y = middle.y - measured.size.height / 2f
            )
        )
    }
}

/** Matches the 72dp play/pause button these sit beside - the drawn ring lands near half its width. */
val SkipIconSize = 46.dp

private const val RADIUS_FRACTION = 0.40f
private const val STROKE_FRACTION = 0.075f
private const val TEXT_FRACTION = 0.38f
private const val ARC_START_DEGREES = -52f
private const val ARC_SWEEP_DEGREES = 308f
private const val ARROW_LENGTH_FACTOR = 2.4f
private const val ARROW_HALF_WIDTH_FACTOR = 1.35f
