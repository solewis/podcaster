package com.solewis.podcaster.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

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

            // Arrowhead capping the arc's counter-clockwise end. Drawn in a frame rotated so that
            // end sits at twelve o'clock, which makes the triangle plain vertical/horizontal
            // arithmetic - deriving it from tangent vectors in the unrotated frame was easy to
            // get subtly wrong and hard to eyeball afterwards.
            val arcEndDegrees = ARC_START_DEGREES + ARC_SWEEP_DEGREES
            rotate(degrees = arcEndDegrees - 270f, pivot = middle) {
                val topY = middle.y - radius
                val halfBase = radius * ARROW_HALF_BASE_FRACTION
                val back = middle.x + radius * ARROW_BACK_FRACTION
                drawPath(
                    path = Path().apply {
                        moveTo(middle.x - radius * ARROW_LENGTH_FRACTION, topY)
                        lineTo(back, topY - halfBase)
                        lineTo(back, topY + halfBase)
                        close()
                    },
                    color = color
                )
            }
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
private const val ARC_START_DEGREES = -60f
private const val ARC_SWEEP_DEGREES = 310f
private const val ARROW_LENGTH_FRACTION = 0.62f
private const val ARROW_HALF_BASE_FRACTION = 0.26f
private const val ARROW_BACK_FRACTION = 0.16f
