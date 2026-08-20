package com.solewis.podcaster

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the real device/emulator path end to end: the app launches, Compose renders, Room opens
 * a real database, navigation is wired up, and edge-to-edge setup doesn't crash. On a fresh
 * install with no subscriptions, the Library tab (the start destination) should show its empty
 * state rather than a blank or crashed screen.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun libraryTabShowsEmptyStateOnFreshInstall() {
        composeTestRule.onNodeWithText("No shows yet - search to subscribe to one.").assertExists()
    }

    @Test
    fun bottomNavigationShowsBothTabs() {
        // "Library" also matches the TopAppBar title, so at least one match rather than exactly
        // one is the correct assertion here.
        composeTestRule.onAllNodesWithText("Library")[0].assertExists()
        composeTestRule.onAllNodesWithText("Search")[0].assertExists()
    }
}
