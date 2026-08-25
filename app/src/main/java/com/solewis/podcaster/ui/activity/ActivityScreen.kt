package com.solewis.podcaster.ui.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.solewis.podcaster.ui.common.ScreenTitle
import com.solewis.podcaster.ui.queue.QueueList
import com.solewis.podcaster.ui.queue.QueueViewModel
import com.solewis.podcaster.ui.subscriptions.SubscriptionsList
import com.solewis.podcaster.ui.subscriptions.SubscriptionsViewModel
import androidx.compose.ui.platform.testTag
import com.solewis.podcaster.ui.common.TestTags

private val SEGMENTS = listOf("Queue", "Subscriptions")

/**
 * Queue and Subscriptions share a tab because both are "what's going on with your listening
 * right now" rather than "browse/discover" (Home, Search) - matching the pattern of showing
 * queue/downloads/history/subscriptions together as one activity surface. History and downloads
 * are natural future segments here; not built yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    queueViewModel: QueueViewModel,
    subscriptionsViewModel: SubscriptionsViewModel,
    onOpenShow: (Long) -> Unit
) {
    // Saveable, not plain remember: navigating into a show and back recreates this
    // composable, and a plain remember would silently drop you back on Queue even though
    // you left from Subscriptions.
    var selectedSegment by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).statusBarsPadding()) {
            ScreenTitle("Activity")
            SecondaryTabRow(selectedTabIndex = selectedSegment) {
                SEGMENTS.forEachIndexed { index, label ->
                    Tab(
                        selected = selectedSegment == index,
                        onClick = { selectedSegment = index },
                        text = { Text(label) },
                        modifier = Modifier.testTag(TestTags.segment(label))
                    )
                }
            }
            when (selectedSegment) {
                0 -> QueueList(viewModel = queueViewModel, modifier = Modifier.fillMaxSize())
                else -> SubscriptionsList(
                    viewModel = subscriptionsViewModel,
                    onOpenShow = onOpenShow,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
