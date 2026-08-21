package com.solewis.podcaster.ui.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.solewis.podcaster.ui.queue.QueueList
import com.solewis.podcaster.ui.queue.QueueViewModel
import com.solewis.podcaster.ui.subscriptions.SubscriptionsList
import com.solewis.podcaster.ui.subscriptions.SubscriptionsViewModel

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
    var selectedSegment by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Activity", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            SecondaryTabRow(selectedTabIndex = selectedSegment) {
                SEGMENTS.forEachIndexed { index, label ->
                    Tab(
                        selected = selectedSegment == index,
                        onClick = { selectedSegment = index },
                        text = { Text(label) }
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
