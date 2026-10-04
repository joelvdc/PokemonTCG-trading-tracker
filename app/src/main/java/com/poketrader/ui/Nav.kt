package com.poketrader.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.runtime.remember
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
    Tab("collection", "Collection", Icons.Default.CollectionsBookmark),
    Tab("scans", "Scan", Icons.Default.CameraAlt),
    Tab("settings", "Settings", Icons.Default.Settings),
)

fun NavController.openSearch(target: CardTarget, query: String? = null) {
    val q = query?.let { "&query=${Uri.encode(it)}" } ?: ""
    navigate("search?target=${Uri.encode(target.encode())}$q")
}

fun NavController.openScanner(target: CardTarget) = navigate("scan?target=${Uri.encode(target.encode())}")

/**
 * Leaves the screen for a Back or Done button, once: a second quick tap while the screen is already
 * closing would otherwise also close the one underneath (and could leave the tabs in a muddle).
 * Returns whether it left.
 */
fun NavController.safePopBackStack(): Boolean =
    currentBackStackEntry?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED && popBackStack()

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val c = LocalContext.current.container
    LaunchedEffect(Unit) {
        c.updater.schedule()
        c.appScope.launch {
            c.updater.autoUpdate()
            c.updater.repairAfterUpgrade()
        }
        // Warm up the set lists the scanner needs.
        c.appScope.launch { runCatching { c.sets.sets("en"); c.sets.sets("ja") } }
        // Pictures for international cards TCGdex has none for.
        c.appScope.launch { runCatching { c.fallbackImages.load() } }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val topLevel = tabs.any { it.route == route }
    val priceState by c.prices.state.collectAsStateWithLifecycle()
    val scanCount by remember { c.db.scanDao().observeCount() }.collectAsStateWithLifecycle(0)
    val catalogState by c.catalog.state.collectAsStateWithLifecycle()
    // Coming back online (e.g. onto Wi-Fi) is a good moment for an update that couldn't run before.
    val reconnects by c.network.reconnects.collectAsStateWithLifecycle()
    LaunchedEffect(reconnects) { if (reconnects > 0) c.appScope.launch { c.updater.autoUpdate() } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (topLevel) {
                Column {
                    PriceStatusStrip(if (priceState == PriceUpdateState.Idle && catalogState is PriceUpdateState.Running) catalogState else priceState)
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
                                icon = {
                                    if (tab.route == "scans" && scanCount > 0) {
                                        BadgedBox(badge = { Badge { Text("$scanCount") } }) { Icon(tab.icon, null) }
                                    } else {
                                        Icon(tab.icon, null)
                                    }
                                },
                                label = { Text(tab.label, maxLines = 1) },
                            )
                        }
                    }
                }
            }
        },
    ) { pad ->
        // Reopened after Android closed the app in the background: don't come back straight into the camera.
        LaunchedEffect(Unit) {
            if (nav.currentDestination?.route?.startsWith("scan?") == true) nav.popBackStack()
        }
        NavHost(nav, startDestination = "trades", modifier = Modifier.padding(pad)) {
            composable("trades") { TradesListScreen(nav) }
            composable("collection") { CollectionScreen(nav) }
            composable("scans") { ScansScreen(nav) }
            composable("settings") { SettingsScreen() }
            composable("value") { ValueScreen(nav) }
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
