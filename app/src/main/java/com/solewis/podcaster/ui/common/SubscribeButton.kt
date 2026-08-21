package com.solewis.podcaster.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

// Smaller and, deliberately, not left/right symmetric: a leading icon this small reads as more
// clearance than the same gap does trailing the word "Subscribe(d)", so the start padding is
// trimmed further than the end to land visually balanced.
private val SubscribeButtonContentPadding = PaddingValues(start = 12.dp, top = 6.dp, end = 16.dp, bottom = 6.dp)

/**
 * The one Subscribe/Subscribed toggle, used everywhere a show can be (un)subscribed from -
 * Search results, the show preview, and the subscribed detail page - rather than each screen
 * inventing its own. Matches the conventional direction (confirmed against Google Podcasts):
 * empty/outlined with a "+" when not subscribed, filled with a check once you are - not the
 * reverse, which is what this had before and read as backwards.
 */
@Composable
fun SubscribeButton(
    isSubscribed: Boolean,
    isBusy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    if (isSubscribed) {
        Button(
            onClick = onClick,
            enabled = enabled && !isBusy,
            modifier = modifier,
            contentPadding = SubscribeButtonContentPadding
        ) {
            SubscribeButtonContent(isBusy = isBusy, icon = Icons.Default.Check, label = "Subscribed")
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled && !isBusy,
            modifier = modifier,
            contentPadding = SubscribeButtonContentPadding
        ) {
            SubscribeButtonContent(isBusy = isBusy, icon = Icons.Default.Add, label = "Subscribe")
        }
    }
}

@Composable
private fun SubscribeButtonContent(isBusy: Boolean, icon: ImageVector, label: String) {
    if (isBusy) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
    } else {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
    }
    Text(label, modifier = Modifier.padding(start = 4.dp))
}
