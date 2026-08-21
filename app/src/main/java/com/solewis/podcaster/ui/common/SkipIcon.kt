package com.solewis.podcaster.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight

/**
 * Material's icon set only ships Replay/Forward glyphs baked for 5/10/30 seconds - not the 15
 * this app uses. Reuses the plain circular-arrow Replay glyph and draws the second count as text
 * on top instead, mirroring the glyph horizontally for the forward direction (turns the
 * counter-clockwise arrow clockwise, same as the built-in ForwardN icons read).
 */
@Composable
fun SkipIcon(seconds: Int, forward: Boolean, contentDescription: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            Icons.Default.Replay,
            contentDescription = contentDescription,
            tint = LocalContentColor.current,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer(scaleX = if (forward) -1f else 1f)
        )
        Text(
            seconds.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}
