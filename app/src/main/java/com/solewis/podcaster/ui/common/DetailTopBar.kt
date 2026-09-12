package com.solewis.podcaster.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A screen's name and its way out, pinned above the content rather than scrolling with it.
 *
 * Replaces a [BackButtonRow] stacked over a [ScreenTitle] on the screens that scroll. Those two
 * sat inside the scrolling content, so on Settings - which is long enough to scroll on any phone -
 * the only way back off the screen disappeared as soon as you started reading it, leaving the
 * system gesture as the sole exit.
 *
 * Carries [statusBarsPadding] itself. That is the other half of what this fixes: the screens using
 * it opt out of the Scaffold's own window insets (so that list content can scroll under the system
 * bars), and one of them had no status-bar padding of its own at all, which left its back button
 * drawn up inside the phone's status bar.
 *
 * Back and title share one line, and the title is a step down from [ScreenTitle]'s size to suit
 * that - a bar is a thing to get past rather than to read, and an oversized one would take real
 * estate from every screen that has this permanently on show.
 */
@Composable
fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    // Opaque, because the content it pins itself above scrolls underneath it.
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.width(BackButtonWidth)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Centred in the bar itself, not in the space left over beside the back button -
                    // those are different places, and the second one reads as very slightly off.
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).testTag(TestTags.screenTitle(title))
                )
                // Balances the back button's width so the title's centre is the bar's centre. Holds
                // whatever `actions` puts here, and is an empty spacer of exactly that width when
                // there is nothing - which is the usual case.
                Row(
                    modifier = Modifier.widthIn(min = BackButtonWidth),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions
                )
            }
            HorizontalDivider()
        }
    }
}

/** The back button's footprint, mirrored on the trailing side so the title centres on the bar. */
private val BackButtonWidth = 48.dp
