package com.solewis.podcaster.ui.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.solewis.podcaster.testing.FeedHost
import com.solewis.podcaster.testing.TestGraph
import com.solewis.podcaster.testing.podcastRow
import com.solewis.podcaster.ui.PodcasterRoot
import com.solewis.podcaster.ui.theme.PodcasterTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bringing the app to the foreground checks the library, so new episodes are simply there. Before
 * this the only automatic path was a six-hourly `RefreshAllWorker` - and later than that whenever
 * Doze deferred it - which meant opening the app usually showed a feed from hours ago until you
 * went looking for a refresh control.
 *
 * Only the wiring is proved here: that `PodcasterRoot` actually reaches the repository on
 * `ON_START`. Whether a given show is due is `SubscriptionRepositoryTest`'s job, where the clock is
 * injectable and the skip case can be pinned properly.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundRefreshTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var graph: TestGraph
    private lateinit var host: FeedHost

    @Before
    fun setUp() {
        graph = TestGraph()
        host = FeedHost()
    }

    @After
    fun tearDown() {
        host.close()
        graph.close()
    }

    @Test
    fun opening_the_app_checks_a_subscription_that_is_overdue() {
        runBlocking {
            // Never checked, so overdue under any clock - and this container's repository runs on
            // the real one, unlike the hand-built repositories elsewhere in these tests.
            graph.db.podcastDao().insert(podcastRow(feedUrl = host.feedUrl(), lastRefreshedAt = null))
        }
        host.enqueueNotModified()
        val container = graph.appContainer()

        compose.setContent { PodcasterTheme { PodcasterRoot(container = container) } }

        compose.waitUntil(timeoutMillis = 5_000) { host.requestCount == 1 }
    }
}
