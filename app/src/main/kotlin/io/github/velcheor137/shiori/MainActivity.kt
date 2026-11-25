package io.github.velcheor137.shiori

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
            ShioriTheme {
                Surface(Modifier.fillMaxSize()) { ShioriNav() }
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

@Composable
private fun ShioriNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = route in tabs.map { it.route }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(Modifier.weight(1f)) {
            NavHost(nav, startDestination = "home") {
                composable("home") { HomeScreen(nav) }
                composable("catalog") { CatalogScreen(nav) }
                composable("library") { LibraryScreen(nav) }
                composable("profile") { ProfileScreen(nav) }
                composable("title/{slug}", arguments = listOf(navArgument("slug") { type = NavType.StringType })) { e ->
                    TitleScreen(nav, e.arguments?.getString("slug").orEmpty())
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
                    ReaderScreen(
                        nav = nav,
                        slug = a.getString("slug").orEmpty(),
                        branchId = a.getLong("branch"),
                        volume = a.getString("volume").orEmpty(),
                        number = a.getString("number").orEmpty(),
                        restore = a.getBoolean("restore"),
                        startBlock = a.getInt("block"),
                    )
                }
                composable("download/{slug}", arguments = listOf(navArgument("slug") { type = NavType.StringType })) { e ->
                    DownloadScreen(nav, e.arguments?.getString("slug").orEmpty())
                }
                composable("downloads") { DownloadsOverviewScreen(nav) }
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

