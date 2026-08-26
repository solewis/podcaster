package com.solewis.podcaster.ui.common

import com.solewis.podcaster.data.repo.DownloadStatus
import com.solewis.podcaster.data.settings.SkipAmount
import com.solewis.podcaster.data.settings.ThemeMode

/**
 * Stable handles for UI tests.
 *
 * Deliberately few, and only on structural nodes - which screen is showing, which tab is selected,
 * the list containers. Everything else a test needs is already addressable by the text or content
 * description a person would read, and tagging that too would just be duplication that can drift.
 *
 * These exist because visible text is not reliably unique: the Activity tab's title and its
 * navigation-bar item both render the word "Activity", so a text matcher cannot tell "the Activity
 * tab is on screen" from "the Activity button exists in the bottom bar" - which is precisely the
 * distinction the navigation tests are about.
 */
object TestTags {

    fun navTab(label: String) = "navTab:$label"

    /** On a tab root's own heading, so a test can assert which tab it actually landed on. */
    fun screenTitle(text: String) = "screenTitle:$text"

    /** Activity's Queue/Subscriptions segments. */
    fun segment(label: String) = "segment:$label"

    const val SHOW_SCREEN = "showScreen"
    const val EPISODE_DETAIL_SCREEN = "episodeDetailScreen"
    const val MINI_PLAYER = "miniPlayer"
    const val RESUME_PILL = "resumePill"
    const val DOWNLOADS_LIST = "downloadsList"
    const val SETTINGS_SCREEN = "settingsScreen"
    const val SETTINGS_BUTTON = "settingsButton"
    const val AUTO_ADVANCE_SWITCH = "autoAdvanceSwitch"

    /** Direction included: the two rows are identical apart from it, which is the bug worth catching. */
    fun skipChoice(forward: Boolean, amount: SkipAmount) =
        "skipChoice:${if (forward) "forward" else "back"}:${amount.seconds}"

    fun themeChoice(mode: ThemeMode) = "themeChoice:${mode.name}"

    /**
     * Carries the state, not just the identity: the whole risk with a one-control-many-states
     * button is that it renders the wrong state, which a tag naming only "the download button"
     * could never catch.
     */
    fun downloadButton(status: DownloadStatus?) = "downloadButton:${status?.name ?: "NONE"}"
}
