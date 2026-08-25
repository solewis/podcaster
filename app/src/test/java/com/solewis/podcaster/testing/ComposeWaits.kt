package com.solewis.podcaster.testing

import androidx.compose.ui.test.junit4.ComposeTestRule
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
