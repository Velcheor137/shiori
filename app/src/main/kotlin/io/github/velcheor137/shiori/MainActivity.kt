package io.github.velcheor137.shiori

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by shiori.store.appTheme.collectAsStateWithLifecycle()
            val dark = theme == AppTheme.Dark || (theme == AppTheme.System && isSystemInDarkTheme())
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            ShioriTheme {
                // Смена источника — новый граф навигации: экраны и их модели создаются заново для нового сайта.
                val source by shiori.source.collectAsState()
                Surface(Modifier.fillMaxSize()) { androidx.compose.runtime.key(source) { ShioriNav("home") } }
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("home", "Главная", androidx.compose.material.icons.Icons.Filled.Home),
    Tab("catalog", "Каталог", AppIcons.Catalog),
    Tab("library", "Библиотека", AppIcons.Library),
    Tab("profile", "Профиль", androidx.compose.material.icons.Icons.Filled.Person),
)

object Routes {
    fun title(slug: String) = "title/${Uri.encode(slug)}"
    fun reader(slug: String, branchId: Long, volume: String, number: String, restore: Boolean = false, startBlock: Int = -1) =
        "reader/${Uri.encode(slug)}/$branchId/${Uri.encode(volume)}/${Uri.encode(number)}?restore=$restore&block=$startBlock"
    fun download(slug: String) = "download/${Uri.encode(slug)}"
}

private const val NAV_MS = 260
private val tabRoutes = tabs.map { it.route }

/** Непрозрачный фон экрана: при переходе и жесте «назад» старый экран не просвечивает. */
@Composable
private fun Opaque(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
}

@Composable
private fun ShioriNav(start: String) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = route in tabs.map { it.route }
    val immersive = route?.startsWith("reader") == true
    Column(Modifier.fillMaxSize().then(if (immersive) Modifier else Modifier.safeDrawingPadding())) {
        Box(Modifier.weight(1f)) {
            NavHost(
                nav,
                startDestination = start,
                // Короткий сдвиг с затуханием вместо стандартного медленного fade; экраны непрозрачные (см. screen()).
                enterTransition = {
                    if (initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes) fadeIn(tween(NAV_MS))
                    else slideInHorizontally(tween(NAV_MS, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(NAV_MS))
                },
                exitTransition = {
                    if (initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes) fadeOut(tween(NAV_MS / 2))
                    else slideOutHorizontally(tween(NAV_MS, easing = FastOutSlowInEasing)) { -it / 10 } + fadeOut(tween(NAV_MS, delayMillis = NAV_MS / 3))
                },
                popEnterTransition = {
                    slideInHorizontally(tween(NAV_MS, easing = FastOutSlowInEasing)) { -it / 10 }
                },
                popExitTransition = {
                    slideOutHorizontally(tween(NAV_MS, easing = FastOutSlowInEasing)) { it }
                },
                // Жест «назад» (predictive back, Android 14+): экран уезжает вправо за пальцем, без уменьшения.
                predictivePopEnterTransition = {
                    slideInHorizontally(tween(NAV_MS, easing = LinearEasing)) { -it / 10 }
                },
                predictivePopExitTransition = {
                    slideOutHorizontally(tween(NAV_MS, easing = LinearEasing)) { it }
                },
            ) {
                composable("home") { Opaque { HomeScreen(nav) } }
                composable("catalog") { Opaque { CatalogScreen(nav) } }
                composable("library") { Opaque { LibraryScreen(nav) } }
                composable("profile") { Opaque { ProfileScreen(nav) } }
                composable("title/{slug}", arguments = listOf(navArgument("slug") { type = NavType.StringType })) { e ->
                    Opaque { TitleScreen(nav, e.arguments?.getString("slug").orEmpty()) }
                }
                composable(
                    "reader/{slug}/{branch}/{volume}/{number}?restore={restore}&block={block}",
                    arguments = listOf(
                        navArgument("slug") { type = NavType.StringType },
                        navArgument("branch") { type = NavType.LongType },
                        navArgument("volume") { type = NavType.StringType },
                        navArgument("number") { type = NavType.StringType },
                        navArgument("restore") { type = NavType.BoolType; defaultValue = false },
                        navArgument("block") { type = NavType.IntType; defaultValue = -1 },
                    ),
                ) { e ->
                    val a = e.arguments!!
                    Opaque { ReaderScreen(
                        nav = nav,
                        slug = a.getString("slug").orEmpty(),
                        branchId = a.getLong("branch"),
                        volume = a.getString("volume").orEmpty(),
                        number = a.getString("number").orEmpty(),
                        restore = a.getBoolean("restore"),
                        startBlock = a.getInt("block"),
                    ) }
                }
                composable("download/{slug}", arguments = listOf(navArgument("slug") { type = NavType.StringType })) { e ->
                    Opaque { DownloadScreen(nav, e.arguments?.getString("slug").orEmpty()) }
                }
                composable("downloads") { Opaque { DownloadsOverviewScreen(nav) } }
                composable("notifications") { Opaque { NotificationsScreen(nav) } }
                composable("storage") { Opaque { StorageScreen(nav) } }
            }
        }
        if (showBar) BottomBar(nav, route)
    }
}

@Composable
private fun BottomBar(nav: NavHostController, current: String?) {
    NavigationBar {
        tabs.forEach { t ->
            NavigationBarItem(
                selected = current == t.route,
                onClick = {
                    if (current != t.route) {
                        nav.navigate(t.route) {
                            popUpTo("home") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { androidx.compose.material3.Icon(t.icon, null) },
                label = { Text(t.label) },
            )
        }
    }
}

