package com.poketrader.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.poketrader.container
import com.poketrader.data.CardTarget
import com.poketrader.data.PriceUpdateState
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("trades", "Trades", Icons.Default.SwapHoriz),
    Tab("collection", "My cards", Icons.Default.CollectionsBookmark),
    Tab("settings", "Settings", Icons.Default.Settings),
)

fun NavController.openSearch(target: CardTarget, query: String? = null) {
    val q = query?.let { "&query=${Uri.encode(it)}" } ?: ""
    navigate("search?target=${Uri.encode(target.encode())}$q")
}

fun NavController.openScanner(target: CardTarget) = navigate("scan?target=${Uri.encode(target.encode())}")

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val c = LocalContext.current.container
    LaunchedEffect(Unit) {
        c.appScope.launch { c.prices.refreshIfStale() }
        // Warm up the set lists the scanner needs.
        c.appScope.launch { runCatching { c.sets.sets("en"); c.sets.sets("ja") } }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val topLevel = tabs.any { it.route == route }
    val priceState by c.prices.state.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (topLevel) {
                Column {
                    PriceStatusStrip(priceState)
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "trades", modifier = Modifier.padding(pad)) {
            composable("trades") { TradesListScreen(nav) }
            composable("collection") { CollectionScreen(nav) }
            composable("settings") { SettingsScreen() }
            composable("trade/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                TradeEditorScreen(nav, it.arguments?.getLong("id") ?: 0L)
            }
            composable(
                "search?target={target}&query={query}",
                arguments = listOf(
                    navArgument("target") { type = NavType.StringType; defaultValue = "collection" },
                    navArgument("query") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) {
                SearchScreen(
                    nav,
                    CardTarget.decode(it.arguments?.getString("target") ?: "collection"),
                    it.arguments?.getString("query"),
                )
            }
            composable(
                "scan?target={target}",
                arguments = listOf(navArgument("target") { type = NavType.StringType; defaultValue = "collection" }),
            ) {
                ScannerScreen(nav, CardTarget.decode(it.arguments?.getString("target") ?: "collection"))
            }
        }
    }
}

@Composable
private fun PriceStatusStrip(state: PriceUpdateState) {
    when (state) {
        is PriceUpdateState.Running -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(state.message, style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        is PriceUpdateState.Failed -> Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Text(
                "Price update failed: ${state.message}. Retry in Settings.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        PriceUpdateState.Idle -> {}
    }
}
