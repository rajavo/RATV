package com.ratv.app.ui

import android.net.Uri
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import com.ratv.app.ui.components.TopBar
import com.ratv.app.ui.components.rememberIsWide
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

    /** Login screen for one more user; the current one stays and Back returns to it. */
    fun addUser() = nav.navigate("adduser")

    /** Loading screen, then home: used after switching users. */
    fun reload() {
        nav.navigate("splash") {
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
    val wide = rememberIsWide()
    val currentTab = if (route == "guide") "live" else (route ?: "home")
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    Column(
        Modifier
            .fillMaxSize()
            .background(RColors.Ink)
            // With a remote, Back on a selected item would only clear the selection;
            // send it straight to the normal back handling instead.
            .onKeyEvent { e ->
                if (e.key != Key.Back || backDispatcher == null) return@onKeyEvent false
                if (e.type == KeyEventType.KeyUp) backDispatcher.onBackPressed()
                true
            }
    ) {
    if (wide && showBar) {
        TopBar(current = currentTab, onSelect = { navigator.tab(it) }, onSearch = { navigator.search() })
    }
    Box(Modifier.weight(1f)) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = RColors.Ink,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar && !wide) {
                BottomNav(current = currentTab, onSelect = { navigator.tab(it) })
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
            composable("adduser") { LoginScreen(onDone = { navigator.home() }, onBack = { navigator.back() }) }
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
    }
}
