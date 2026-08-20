package com.solewis.podcaster.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
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
import com.solewis.podcaster.ui.common.MiniPlayer
import com.solewis.podcaster.ui.library.LibraryScreen
import com.solewis.podcaster.ui.library.LibraryViewModel
import com.solewis.podcaster.ui.search.SearchScreen
import com.solewis.podcaster.ui.search.SearchViewModel
import com.solewis.podcaster.ui.show.ShowScreen
import com.solewis.podcaster.ui.show.ShowViewModel
import kotlinx.coroutines.launch

@Composable
fun PodcasterRoot(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val scope = rememberCoroutineScope()

    val playback by container.playerConnection.state.collectAsState()

    val topLevelRoutes = listOf(
        TopLevelRoute(Route.Library, "Library", Icons.Default.LibraryMusic),
        TopLevelRoute(Route.Search, "Search", Icons.Default.Search)
    )

    Scaffold(
        bottomBar = {
            Column {
                MiniPlayer(
                    playback = playback,
                    onTogglePlayPause = { scope.launch { container.playerConnection.togglePlayPause() } }
                )
                NavigationBar {
                    topLevelRoutes.forEach { topLevel ->
                        val selected = currentDestination?.hierarchy?.any { it.hasRoute(topLevel.route::class) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(topLevel.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(topLevel.icon, contentDescription = topLevel.label) },
                            label = { Text(topLevel.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Route.Library,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable<Route.Library> {
                val viewModel: LibraryViewModel = viewModel(
                    factory = viewModelFactory { initializer { LibraryViewModel(container.podcastRepository) } }
                )
                LibraryScreen(viewModel = viewModel, onOpenShow = { podcastId ->
                    navController.navigate(Route.Show(podcastId))
                })
            }
            composable<Route.Search> {
                val viewModel: SearchViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { SearchViewModel(container.searchRepository, container.subscriptionRepository) }
                    }
                )
                SearchScreen(viewModel = viewModel)
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
                                container.playerConnection
                            )
                        }
                    }
                )
                ShowScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
        }
    }
}

private data class TopLevelRoute(val route: Route, val label: String, val icon: ImageVector)
