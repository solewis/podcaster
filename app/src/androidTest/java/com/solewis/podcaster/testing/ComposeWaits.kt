package com.solewis.podcaster.testing

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText

/**
 * Waits for content that arrives from Room.
 *
 * `waitForIdle()` and `assertIsDisplayed()` are not enough on their own: Compose's idling only knows
 * about its own recomposition, not about a Room query resolving on a background thread, so an
 * assertion made immediately after launch can read the screen before its first emission has landed.
 * That is a flake, and it showed up exactly as one - passing when the class ran alone and failing in
 * the full suite, where startup was warmer and the assertion got there first.
 */
fun ComposeTestRule.awaitText(text: String, timeoutMillis: Long = TIMEOUT_MILLIS) {
    waitUntil(timeoutMillis) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
}

fun ComposeTestRule.awaitTag(tag: String, timeoutMillis: Long = TIMEOUT_MILLIS) {
    waitUntil(timeoutMillis) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
}

private const val TIMEOUT_MILLIS = 10_000L
