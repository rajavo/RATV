package com.ratv.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ratv.app.data.Repository
import com.ratv.app.player.PlayerScreen
import com.ratv.app.ui.components.BottomNav
import com.ratv.app.ui.screens.GuideScreen
import com.ratv.app.ui.screens.HomeScreen
import com.ratv.app.ui.screens.LiveScreen
import com.ratv.app.ui.screens.LoginScreen
import com.ratv.app.ui.screens.MovieDetailScreen
import com.ratv.app.ui.screens.MoviesScreen
import com.ratv.app.ui.screens.SearchScreen
import com.ratv.app.ui.screens.SeriesDetailScreen
import com.ratv.app.ui.screens.SeriesScreen
import com.ratv.app.ui.screens.SettingsScreen
import com.ratv.app.ui.screens.SplashScreen
import com.ratv.app.ui.theme.RColors

val LocalRepo = staticCompositionLocalOf<Repository> { error("Repository not provided") }

/** All navigation actions used by the screens. */
class Navigator(private val nav: NavHostController) {
    fun back() {
        nav.popBackStack()
    }

    fun tab(route: String) {
        nav.navigate(route) {
            popUpTo("home") { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun home() {
        nav.navigate("home") {
            popUpTo(nav.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    fun login() {
        nav.navigate("login") {
            popUpTo(nav.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    fun search() = nav.navigate("search")
    fun guide() = nav.navigate("guide") { launchSingleTop = true }
    fun movie(id: String) = nav.navigate("movie/${Uri.encode(id)}")
    fun show(id: String) = nav.navigate("show/${Uri.encode(id)}")
    fun playLive(id: String) = nav.navigate("player/live/${Uri.encode(id)}")
    fun playMovie(id: String) = nav.navigate("player/movie/${Uri.encode(id)}")
    fun playEpisode(seriesId: String, episodeId: String) =
        nav.navigate("player/episode/${Uri.encode(episodeId)}?series=${Uri.encode(seriesId)}")
    fun resume(key: String) = nav.navigate("player/resume/${Uri.encode(key)}")
}

private val barRoutes = setOf("home", "live", "movies", "series", "settings", "guide")

@Composable
fun RatvRoot() {
    val nav = rememberNavController()
    val navigator = remember(nav) { Navigator(nav) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = route in barRoutes

    Scaffold(
        containerColor = RColors.Ink,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) {
                BottomNav(
                    current = if (route == "guide") "live" else (route ?: "home"),
                    onSelect = { navigator.tab(it) }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "splash",
            modifier = Modifier.padding(padding)
        ) {
            composable("splash") {
                SplashScreen(
                    onNeedLogin = { navigator.login() },
                    onReady = { navigator.home() },
                    onAutoPlay = { id ->
                        navigator.home()
                        navigator.playLive(id)
                    }
                )
            }
            composable("login") { LoginScreen(onDone = { navigator.home() }) }
            composable("home") { HomeScreen(navigator) }
            composable("live") { LiveScreen(navigator) }
            composable("guide") { GuideScreen(navigator) }
            composable("movies") { MoviesScreen(navigator) }
            composable("series") { SeriesScreen(navigator) }
            composable("settings") { SettingsScreen(navigator) }
            composable("search") { SearchScreen(navigator) }
            composable(
                "movie/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { backStack ->
                MovieDetailScreen(backStack.arguments?.getString("id").orEmpty(), navigator)
            }
            composable(
                "show/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { backStack ->
                SeriesDetailScreen(backStack.arguments?.getString("id").orEmpty(), navigator)
            }
            composable(
                "player/{kind}/{id}?series={series}",
                arguments = listOf(
                    navArgument("kind") { type = NavType.StringType },
                    navArgument("id") { type = NavType.StringType },
                    navArgument("series") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStack ->
                val args = backStack.arguments
                PlayerScreen(
                    kind = args?.getString("kind").orEmpty(),
                    id = args?.getString("id").orEmpty(),
                    seriesId = args?.getString("series"),
                    navigator = navigator
                )
            }
        }
    }
}
