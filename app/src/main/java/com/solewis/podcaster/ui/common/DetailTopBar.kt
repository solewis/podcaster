package com.solewis.podcaster.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
    /**
     * Off, the bar keeps its shape and its back button but shows no name. For a screen that already
     * prints the title in its content, this is how the bar takes the title over once the content's
     * own copy has scrolled away - faded rather than swapped, so the two never both read as the
     * heading and nothing jumps when they trade.
     */
    showTitle: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {}
) {
    // Opaque, because the content it pins itself above scrolls underneath it. That opacity is the
    // whole separation now - there is no rule under the bar. A divider drew a hard line across
    // every one of these screens to mark a boundary the surface colour already makes, and on the
    // screens that simply do not scroll it was a line for no reason at all.
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.width(BackButtonWidth)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            // Composed away rather than made transparent when hidden: an invisible heading
            // that a screen reader still announces is not hidden in any sense that matters, and
            // it is the difference between a test being able to say "there is no title here"
            // and not. The weight stays on the AnimatedVisibility itself, so the slot holds its
            // width either way and the back button does not shift when a title arrives.
            AnimatedVisibility(
                visible = showTitle,
                modifier = Modifier.weight(1f),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    title,
                    // A step down the scale from a screen's own heading. The bar is a place to get
                    // your bearings and get out of, not something to read, and on the detail screen
                    // this text is standing in for a headline that was bigger still - matching that
                    // size would make the swap look like the heading had simply moved up.
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Centred in the bar itself, not in the space left over beside the back
                    // button - those are different places, and the second one reads as very
                    // slightly off.
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.screenTitle(title))
                )
            }
            // Balances the back button's width so the title's centre is the bar's centre. Holds
            // whatever `actions` puts here, and is an empty spacer of exactly that width when
            // there is nothing - which is the usual case.
            Row(
                modifier = Modifier.widthIn(min = BackButtonWidth),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}

/** The back button's footprint, mirrored on the trailing side so the title centres on the bar. */
private val BackButtonWidth = 48.dp
