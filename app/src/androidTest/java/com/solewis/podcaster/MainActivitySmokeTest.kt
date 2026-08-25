package com.solewis.podcaster

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.solewis.podcaster.data.db.PodcasterDatabase
import com.solewis.podcaster.testing.awaitTag
import com.solewis.podcaster.testing.awaitText
import com.solewis.podcaster.testing.episodeRow
import com.solewis.podcaster.testing.inMemoryTestDatabase
import com.solewis.podcaster.testing.podcastRow
import com.solewis.podcaster.ui.common.TestTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the real device path end to end: the app launches, Compose renders, Room opens a real
 * database, navigation is wired up, and edge-to-edge setup doesn't crash.
 *
 * Runs against a database this test owns. It used to read the app's shared on-disk library, which
 * made its "no subscriptions yet" assertion really an assertion about whatever device happened to
 * be running it - and it duly failed the first time a subscription was added by hand, for no reason
 * connected to any code change.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    /** Empty, not `createAndroidComposeRule`: the container has to be installed *before* the
     * Activity starts, and that rule launches during its own evaluation. */
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<PodcasterApp>()
    private var db: PodcasterDatabase? = null
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        db?.close()
    }

    private fun launchWith(seed: suspend (PodcasterDatabase) -> Unit = {}) {
        val database = inMemoryTestDatabase(app)
        db = database
        runBlocking { seed(database) }
        app.installContainer(AppContainer(app, database = database))
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @Test
    fun a_fresh_library_shows_its_empty_state() {
        launchWith()

        compose.awaitText("No shows yet - search to subscribe to one.")
        compose.onNodeWithText("No shows yet - search to subscribe to one.").assertIsDisplayed()
    }

    @Test
    fun every_tab_is_reachable_from_the_bottom_bar() {
        launchWith()

        compose.awaitTag(TestTags.navTab("Home"))
        compose.onNodeWithTag(TestTags.navTab("Home")).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.navTab("Activity")).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.navTab("Search")).assertIsDisplayed()
    }

    @Test
    fun a_seeded_library_renders_through_the_real_activity() {
        launchWith { database ->
            val podcastId = database.podcastDao().insert(podcastRow(title = "Radiolab"))
            database.episodeDao().insertNew(listOf(episodeRow(podcastId, "1", title = "Patient Zero")))
        }

        // The substituted container has to reach the app itself, not just this test - if the
        // Activity were still reading the real database this would show the empty state.
        compose.awaitText("Radiolab")
        compose.onNodeWithText("Radiolab").assertIsDisplayed()
        compose.onNodeWithText("Patient Zero").assertIsDisplayed()
    }

    @Test
    fun an_episode_opens_its_detail_screen_on_a_real_device() {
        launchWith { database ->
            val podcastId = database.podcastDao().insert(podcastRow(title = "Radiolab"))
            database.episodeDao().insertNew(
                listOf(episodeRow(podcastId, "1", title = "Patient Zero", durationMillis = 600_000))
            )
        }

        compose.awaitText("Patient Zero")
        compose.onNodeWithText("Patient Zero").performClick()

        compose.onNodeWithTag(TestTags.EPISODE_DETAIL_SCREEN).assertIsDisplayed()
        compose.onNodeWithText("Play").assertIsDisplayed()
    }
}
