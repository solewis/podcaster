package com.solewis.podcaster.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.solewis.podcaster.AppContainer
import com.solewis.podcaster.ui.activity.ActivityScreen
import com.solewis.podcaster.ui.common.MiniPlayer
import com.solewis.podcaster.ui.episodedetail.EpisodeDetailScreen
import com.solewis.podcaster.ui.episodedetail.EpisodeDetailViewModel
import com.solewis.podcaster.ui.home.HomeScreen
import com.solewis.podcaster.ui.home.HomeViewModel
import com.solewis.podcaster.ui.nowplaying.NowPlayingScreen
import com.solewis.podcaster.ui.nowplaying.NowPlayingViewModel
import com.solewis.podcaster.ui.queue.QueueViewModel
import com.solewis.podcaster.ui.search.SearchScreen
import com.solewis.podcaster.ui.search.SearchViewModel
import com.solewis.podcaster.ui.show.ShowScreen
import com.solewis.podcaster.ui.show.ShowViewModel
import com.solewis.podcaster.ui.showpreview.ShowPreviewScreen
import com.solewis.podcaster.ui.showpreview.ShowPreviewViewModel
import com.solewis.podcaster.ui.subscriptions.SubscriptionsViewModel
import kotlinx.coroutines.launch

@Composable
fun PodcasterRoot(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val scope = rememberCoroutineScope()

    val playback by container.playerConnection.state.collectAsState()
    val playbackProgress by container.playerConnection.progress.collectAsState()

    val topLevelRoutes = listOf(
        TopLevelRoute(Route.Home, "Home", Icons.Default.Home),
        TopLevelRoute(Route.Activity, "Activity", Icons.AutoMirrored.Filled.PlaylistPlay),
        TopLevelRoute(Route.Search, "Search", Icons.Default.Search)
    )
    // Hidden on Now Playing itself - showing a mini player and tab bar over the full player
    // screen is redundant chrome covering the very thing you navigated there to see.
    val isNowPlaying = currentDestination?.hasRoute(Route.NowPlaying::class) == true

    Scaffold(
        // Leaves the status bar inset for each screen to handle itself (most via ScreenTitle/
        // BackButtonRow's own windowInsetsPadding) rather than reserving it here too - every
        // screen nests its own Scaffold below this one, and each of those defaults to reserving
        // the *same* safe-drawing insets again, so reserving it at both levels was quietly
        // doubling the status-bar-sized gap at the top of every single screen.
        contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.statusBars),
        bottomBar = {
            if (!isNowPlaying) {
                Column {
                    MiniPlayer(
                        playback = playback,
                        progress = playbackProgress,
                        onTogglePlayPause = { scope.launch { container.playerConnection.togglePlayPause() } },
                        onExpand = { navController.navigate(Route.NowPlaying) }
                    )
                    // Same base color as the screen behind it (background == surface in this
                    // theme) - tonalElevation still lifts it a touch via M3's surface-tint blend,
                    // rather than a visibly distinct color block.
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        topLevelRoutes.forEach { topLevel ->
                            val selected = currentDestination?.hierarchy?.any { it.hasRoute(topLevel.route::class) } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    // A tab tap always lands on that tab's root, discarding
                                    // anything pushed on top of it.
                                    //
                                    // The usual saveState/restoreState pair is deliberately not
                                    // used: popping with saveState and then navigating with
                                    // restoreState round-trips the very entries just popped, so
                                    // tapping Home from an episode's detail screen saved and then
                                    // restored that screen and appeared to do nothing at all.
                                    navController.navigate(topLevel.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = false
                                        }
                                        launchSingleTop = true
                                        restoreState = false
                                    }
                                },
                                icon = { Icon(topLevel.icon, contentDescription = topLevel.label) },
                                label = { Text(topLevel.label) }
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Route.Home,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable<Route.Home> {
                val viewModel: HomeViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            HomeViewModel(
                                container.podcastRepository,
                                container.episodeRepository,
                                container.queueRepository,
                                container.playerConnection
                            )
                        }
                    }
                )
                HomeScreen(
                    viewModel = viewModel,
                    onOpenShow = { podcastId -> navController.navigate(Route.Show(podcastId)) },
                    onOpenEpisode = { episodeId -> navController.navigate(Route.EpisodeDetail(episodeId)) }
                )
            }
            composable<Route.Activity> {
                val queueViewModel: QueueViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { QueueViewModel(container.queueRepository, container.playerConnection) }
                    }
                )
                val subscriptionsViewModel: SubscriptionsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { SubscriptionsViewModel(container.podcastRepository, container.subscriptionRepository) }
                    }
                )
                ActivityScreen(
                    queueViewModel = queueViewModel,
                    subscriptionsViewModel = subscriptionsViewModel,
                    onOpenShow = { podcastId -> navController.navigate(Route.Show(podcastId)) }
                )
            }
            composable<Route.Search> {
                val viewModel: SearchViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            SearchViewModel(
                                container.searchRepository,
                                container.subscriptionRepository,
                                container.podcastRepository
                            )
                        }
                    }
                )
                SearchScreen(
                    viewModel = viewModel,
                    onOpenShow = { result ->
                        navController.navigate(
                            Route.ShowPreview(
                                feedUrl = result.feedUrl,
                                itunesCollectionId = result.itunesCollectionId,
                                title = result.title,
                                author = result.author,
                                artworkUrl = result.artworkUrl
                            )
                        )
                    }
                )
            }
            composable<Route.ShowPreview> { entry ->
                val route = entry.toRoute<Route.ShowPreview>()
                val viewModel: ShowPreviewViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            ShowPreviewViewModel(
                                feedUrl = route.feedUrl,
                                itunesCollectionId = route.itunesCollectionId,
                                seedTitle = route.title,
                                seedArtworkUrl = route.artworkUrl,
                                showPreviewRepository = container.showPreviewRepository,
                                subscriptionRepository = container.subscriptionRepository,
                                podcastRepository = container.podcastRepository,
                                playerConnection = container.playerConnection
                            )
                        }
                    }
                )
                ShowPreviewScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onSubscribed = { podcastId ->
                        navController.navigate(Route.Show(podcastId)) {
                            popUpTo<Route.ShowPreview> { inclusive = true }
                        }
                    }
                )
            }
            composable<Route.Show> { entry ->
                val route = entry.toRoute<Route.Show>()
                val viewModel: ShowViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            ShowViewModel(
                                route.podcastId,
                                container.podcastRepository,
                                container.episodeRepository,
                                container.subscriptionRepository,
                                container.queueRepository,
                                container.playerConnection
                            )
                        }
                    }
                )
                ShowScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenEpisode = { episodeId -> navController.navigate(Route.EpisodeDetail(episodeId)) }
                )
            }
            composable<Route.EpisodeDetail> { entry ->
                val route = entry.toRoute<Route.EpisodeDetail>()
                val viewModel: EpisodeDetailViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            EpisodeDetailViewModel(
                                episodeId = route.episodeId,
                                episodeRepository = container.episodeRepository,
                                queueRepository = container.queueRepository,
                                playerConnection = container.playerConnection
                            )
                        }
                    }
                )
                EpisodeDetailScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable<Route.NowPlaying> {
                val viewModel: NowPlayingViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { NowPlayingViewModel(container.playerConnection) }
                    }
                )
                NowPlayingScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
        }
    }
}

private data class TopLevelRoute(val route: Route, val label: String, val icon: ImageVector)
