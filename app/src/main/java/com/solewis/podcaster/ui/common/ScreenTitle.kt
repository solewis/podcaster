package com.solewis.podcaster.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A tab root's title, deliberately not a `TopAppBar`: that reserves a fixed 64dp-tall bar and
 * centers the title inside it regardless of how big the title text actually is, which is what
 * left a large dead gap between the status bar and the title on every tab. This just sits at
 * the top of the content with normal padding, so the empty space above it is exactly what the
 * status bar itself takes - nothing more.
 */
@Composable
fun ScreenTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
    )
}
