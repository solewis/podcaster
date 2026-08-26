package com.solewis.podcaster.testing

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText

/**
 * Waits for content that arrives from Room.
 *
 * `waitForIdle()` is not enough on its own: Compose's idling only covers its own recomposition, not
 * a Room query resolving off-thread, so an assertion or click made straight after launch can reach
 * the screen before its first emission. That failure mode is a flake rather than an error - it
 * showed up on device as a test that passed alone and failed in the full suite - so these waits are
 * here even where the tests currently pass.
 */
fun ComposeTestRule.awaitText(text: String, timeoutMillis: Long = TIMEOUT_MILLIS) {
    waitUntil(timeoutMillis) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
}

fun ComposeTestRule.awaitTag(tag: String, timeoutMillis: Long = TIMEOUT_MILLIS) {
    waitUntil(timeoutMillis) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
}

private const val TIMEOUT_MILLIS = 10_000L

/**
 * Clicks an episode row by title, aimed at the left of the row rather than its centre.
 *
 * `onNodeWithText` resolves to the row's *merged* node, and `performClick` hits the centre of its
 * bounds - which drifts toward the trailing controls as those grow. A third trailing button once
 * moved that centre onto the buttons and broke navigation tests in a way that looked like a routing
 * bug. Aiming a quarter of the way in keeps these tests about navigation rather than row geometry.
 *
 * The merged node, not the text node: injecting a touch on the text alone does not reach the row's
 * click handler, so the tests silently stop navigating.
 */
fun ComposeContentTestRule.clickEpisodeRow(title: String) {
    onNodeWithText(title).performTouchInput {
        click(Offset(width * 0.25f, height / 2f))
    }
}
