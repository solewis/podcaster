package com.solewis.podcaster

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the real device/emulator path: the app launches, Compose renders, and the theme +
 * edge-to-edge setup don't crash. Deliberately minimal for Increment 1 - this is the harness,
 * not feature coverage.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appNameIsDisplayed() {
        // "Podcaster" itself appears twice (top bar title + headline), so assert on the
        // unique subtitle to prove the whole screen composed rather than just part of it.
        composeTestRule.onNodeWithText("Scaffolding is up. Next: git, tests, CI.").assertExists()
    }
}
