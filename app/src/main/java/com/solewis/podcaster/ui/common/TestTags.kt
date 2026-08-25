package com.solewis.podcaster.ui.common

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
}
