package com.solewis.podcaster.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The message a list shows when it has nothing in it.
 *
 * One component rather than the four hand-rolled copies this replaces, which had drifted into three
 * different treatments - one of them a size and colour smaller than the rest, so switching between
 * the Activity tab's own segments visibly changed the type. Nothing about an empty list is
 * screen-specific, so there was never a reason for them to differ.
 *
 * Muted rather than full-strength text on purpose: an empty state is an explanation, not content.
 */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}
