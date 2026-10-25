package com.poketrader.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import com.poketrader.data.ReceivedClear
import com.poketrader.data.SwipeAction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poketrader.BuildConfig
import com.poketrader.container
import com.poketrader.data.AppCurrency
import com.poketrader.data.ExchangeRates
import com.poketrader.data.PriceSource
import com.poketrader.data.PriceType
import com.poketrader.data.PriceUpdateState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

private val typeHelp = mapOf(
    PriceType.TREND to "Cardmarket's smoothed recent sale price. The usual choice for trades.",
    PriceType.AVG to "Average price of recent sales.",
    PriceType.AVG30 to "Average over the last 30 days — steadier for volatile cards.",
    PriceType.AVG7 to "Average over the last 7 days.",
    PriceType.AVG1 to "Average of yesterday's sales — can jump around.",
    PriceType.LOW to "Cheapest current listing (any condition) — tends to be low.",
)

@Composable
fun SettingsScreen(nav: androidx.navigation.NavController) {
    val c = LocalContext.current.container
    val priceType by c.settings.priceType.collectAsStateWithLifecycle()
    val tolerance by c.settings.tolerancePct.collectAsStateWithLifecycle()
    val lastFetch by c.settings.lastPriceFetch.collectAsStateWithLifecycle()
    val guideDate by c.settings.priceGuideDate.collectAsStateWithLifecycle()
    val count by c.prices.count.collectAsStateWithLifecycle(0)
    val state by c.prices.state.collectAsStateWithLifecycle()
    val catalogState by c.catalog.state.collectAsStateWithLifecycle()
    val catalogCount by c.catalog.count.collectAsStateWithLifecycle(0)
    val catalogFetched by c.settings.catalogFetchedAt.collectAsStateWithLifecycle()
    val autoUpdate by c.settings.autoUpdate.collectAsStateWithLifecycle()
    val wifiOnly by c.settings.wifiOnly.collectAsStateWithLifecycle()
    val themeMode by c.settings.themeMode.collectAsStateWithLifecycle()
    val priceSource by c.settings.priceSource.collectAsStateWithLifecycle()
    val sourceStatus by c.priceSources.status.collectAsStateWithLifecycle()
    val currency by c.settings.currency.collectAsStateWithLifecycle()
    val rates by c.exchangeRates.rates.collectAsStateWithLifecycle()
    val alertType by c.settings.alertPriceType.collectAsStateWithLifecycle()
    val receivedClear by c.settings.receivedClear.collectAsStateWithLifecycle()
    val toCollection by c.settings.receivedToCollection.collectAsStateWithLifecycle()
    val swipeRight by c.settings.swipeRight.collectAsStateWithLifecycle()
    val swipeLeft by c.settings.swipeLeft.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notificationsAllowed by remember { mutableStateOf(c.wishWatch.notifier.allowed()) }
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { notificationsAllowed = c.wishWatch.notifier.allowed() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            PriceSourceSection(priceSource, sourceStatus, c.settings::setPriceSource)

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(if (priceSource == PriceSource.CARDMARKET) "Price used for valuing cards" else "Cardmarket price", style = MaterialTheme.typography.titleMedium)
            if (priceSource != PriceSource.CARDMARKET) {
                Text(
                    "Used for cards TCGplayer has no price for (shown with ≈), and in the comparisons.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PriceType.entries.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().clickable { c.settings.setPriceType(t) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = t == priceType, onClick = { c.settings.setPriceType(t) })
                    Column {
                        Text(t.label)
                        Text(typeHelp[t] ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            CurrencySection(currency, rates, c.settings::setCurrency)

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            com.poketrader.data.ThemeMode.entries.forEach { m ->
                Row(
                    Modifier.fillMaxWidth().clickable { c.settings.setThemeMode(m) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = m == themeMode, onClick = { c.settings.setThemeMode(m) })
                    Text(m.label)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SwipeSection(swipeRight, swipeLeft, c.settings::setSwipe)

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            WishlistAlertsSection(priceSource, alertType, autoUpdate, notificationsAllowed, onAllow = {
                if (android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }) { t -> c.settings.setAlertPriceType(t); c.wishWatch.checkSoon(c.appScope) }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            ReceivedCardsSection(receivedClear, toCollection, c.settings::setReceivedClear, c.settings::setReceivedToCollection)

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("Fair trade margin: ±$tolerance%", style = MaterialTheme.typography.titleMedium)
            Text(
                "Trades whose sides differ by no more than this are shown as fair.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = tolerance.toFloat(),
                onValueChange = { c.settings.setTolerance(it.roundToInt()) },
                valueRange = 0f..20f,
                steps = 19,
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("Cardmarket data", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Price guide date: ${formatGuideDate(guideDate)}")
            Text("Products with prices: $count")
            Text("Prices downloaded: ${if (lastFetch == 0L) "never" else Fmt.dateTime(lastFetch)}")
            Text("Card list: ${if (catalogCount == 0) "not downloaded yet" else "$catalogCount cards, downloaded ${Fmt.dateTime(catalogFetched)}"}")
            listOf(state, catalogState).forEach { s ->
                when (s) {
                    is PriceUpdateState.Running -> Text(s.message, color = MaterialTheme.colorScheme.primary)
                    is PriceUpdateState.Failed -> Text("Last update failed: ${s.message}", color = MaterialTheme.colorScheme.error)
                    PriceUpdateState.Idle -> {}
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { c.appScope.launch { c.updater.updateNow() } },
                enabled = state !is PriceUpdateState.Running && catalogState !is PriceUpdateState.Running && !sourceStatus.running,
            ) { Text("Update now") }
            Spacer(Modifier.height(12.dp))
            SwitchRow(
                title = "Update automatically",
                body = "Prices once a day (about 15 MB) and Cardmarket's card list once a week (about 14 MB), when you open the app and in the background.",
                checked = autoUpdate,
                onChange = { c.settings.setAutoUpdate(it); c.updater.schedule() },
            )
            SwitchRow(
                title = "Only on Wi-Fi",
                body = "Automatic updates wait for Wi-Fi, so they don't use mobile data. “Update now” always works.",
                checked = wifiOnly,
                enabled = autoUpdate,
                onChange = { c.settings.setWifiOnly(it); c.updater.schedule() },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "The card list is used to check TCGdex's links to Cardmarket, so cards TCGdex doesn't link (or links to the wrong card) still get a price. " +
                    "Cards already in a trade keep the price they had when added — use “Refresh prices” in a trade to update them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SyncSection()

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("Backups", style = MaterialTheme.typography.titleMedium)
            Text(
                if (c.backups.lastBackupAt == 0L) "Restore points of all your data, on Nextcloud or on this phone." else "Last backup: ${Fmt.dateTime(c.backups.lastBackupAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { nav.navigate("backups") }) { Text("Backups and restore…") }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text(
                "Card data and pictures come from TCGdex (tcgdex.net); prices come from Cardmarket's public daily Pokémon price guide (EUR). " +
                    "Both are looked up live, so new expansions show up automatically without updating the app.\n\n" +
                    "Unofficial fan app, not affiliated with Nintendo, The Pokémon Company, TCGdex or Cardmarket.\n\nVersion ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Settings → Swiping cards in the collection: what a swipe to the right and to the left does. Since 1.20. */
@Composable
internal fun SwipeSection(right: SwipeAction, left: SwipeAction, onPick: (right: Boolean, SwipeAction) -> Unit) {
    Text("Swiping cards in the collection", style = MaterialTheme.typography.titleMedium)
    Text(
        "In the list and compact views; every swipe can be undone. Press and hold a card to select several (in every view).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    DropdownSelector("Swipe right →", right, SwipeAction.entries.toList(), { it.label }, { onPick(true, it) }, Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    DropdownSelector("← Swipe left", left, SwipeAction.entries.toList(), { it.label }, { onPick(false, it) }, Modifier.fillMaxWidth())
}

/** Settings → Wishlist price alerts: what alerts compare against, and notifications. Since 1.20. */
@Composable
internal fun WishlistAlertsSection(
    source: PriceSource,
    alertType: PriceType,
    autoUpdate: Boolean,
    notificationsAllowed: Boolean,
    onAllow: () -> Unit,
    onAlertType: (PriceType) -> Unit,
) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text("Wishlist price alerts", style = MaterialTheme.typography.titleMedium)
    Text(
        "Open a card on the wishlist and set a price: you get a notification when it costs that much or less " +
            "(for “any version” cards, the cheapest version). Prices are checked when they update, a few times a day.",
        style = small, color = muted,
    )
    if (!autoUpdate) {
        Text(
            "“Update automatically” is off (below), so prices, and alerts, only update when you tap “Update now”.",
            style = small, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (!notificationsAllowed) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications are off for Poké Trader.", Modifier.weight(1f), style = small, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onAllow) { Text("Allow") }
        }
    }
    Spacer(Modifier.height(8.dp))
    if (source == PriceSource.CARDMARKET) {
        DropdownSelector("Cardmarket price for alerts", alertType, PriceType.entries.toList(), { it.label }, onAlertType, Modifier.fillMaxWidth())
        Text(typeHelp[alertType] ?: "", style = small, color = muted)
    } else {
        Text("Alerts use ${source.label}'s price, the price source chosen at the top.", style = small, color = muted)
    }
}

/** Settings → Received wishlist cards: when they're cleared, and whether they also go into the collection. Since 1.20. */
@Composable
internal fun ReceivedCardsSection(clear: ReceivedClear, toCollection: Boolean, onClear: (ReceivedClear) -> Unit, onToCollection: (Boolean) -> Unit) {
    Text("Received wishlist cards", style = MaterialTheme.typography.titleMedium)
    Text(
        "Swipe a wishlist card to the right (or tick “Received” in its window) once it has arrived: it moves to the wishlist's " +
            "Received section, as a record. They're kept until you clear them (⋮ → Clear received cards), or as chosen here.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    DropdownSelector("Clear received cards automatically", clear, ReceivedClear.entries.toList(), { it.label }, onClear, Modifier.fillMaxWidth())
    Spacer(Modifier.height(4.dp))
    SwitchRow(
        title = "Add received cards to the collection",
        body = "Each card goes into Unsorted (near mint, its version) when you mark it received. Off: swipe it on from Received to choose the binder, condition and price.",
        checked = toCollection,
        onChange = onToCollection,
    )
}

@Composable
internal fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun formatGuideDate(raw: String?): String {
    if (raw == null) return "not downloaded yet"
    return try {
        val d = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(raw)
        if (d != null) Fmt.dateTime(d.time) else raw
    } catch (e: Exception) {
        raw
    }
}

/** Settings → Currency: what prices are shown in, and the rates used. Since 1.15. */
@Composable
internal fun CurrencySection(currency: AppCurrency, rates: ExchangeRates?, onPick: (AppCurrency) -> Unit) {
    Text("Currency", style = MaterialTheme.typography.titleMedium)
    Text(
        "Prices come from Cardmarket in euros and are converted with the European Central Bank's daily rates. " +
            "Purchase prices you type are in this currency too. CSV files keep euros.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    AppCurrency.entries.forEach { cur ->
        Row(Modifier.fillMaxWidth().clickable { onPick(cur) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = cur == currency, onClick = { onPick(cur) })
            Text(cur.label)
        }
    }
    if (currency != AppCurrency.EUR) {
        Text(
            rateLine(currency, rates),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "1 € = 7.4603 DKK · rates of 9 Oct 2026 (ECB)". */
internal fun rateLine(currency: AppCurrency, rates: ExchangeRates?): String {
    val rate = rates?.rate(currency)
    return when {
        rate != null -> {
            val day = runCatching {
                java.time.LocalDate.parse(rates.date).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
            }.getOrDefault(rates.date)
            "1 € = %.4f %s · rates of %s (European Central Bank)".format(rate, currency.name, day)
        }
        currency == AppCurrency.DKK -> "1 € ≈ 7.46 DKK (the krone's fixed rate) until the first download of the daily rates."
        else -> "Waiting for the daily exchange rates: prices show in euros until then."
    }
}

/** Settings → Price source: where a card's price comes from. Since 1.16. */
@Composable
internal fun PriceSourceSection(source: PriceSource, status: com.poketrader.data.PriceSourceStore.Status, onPick: (PriceSource) -> Unit) {
    Text("Price source", style = MaterialTheme.typography.titleMedium)
    Text(
        "Where card prices come from: the collection total, sorting, filters and trades use it. The value screen, the stats and each " +
            "card's page compare both. TCGplayer's dollar prices are converted with the daily exchange rates.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val help = mapOf(
        PriceSource.CARDMARKET to "Europe's market, in euros. Has price types and trend arrows.",
        PriceSource.TCGPLAYER to "America's largest market: its market price per version (any condition). English prints only; " +
            "special versions (stamps, patterns, jumbo) use Cardmarket's.",
    )
    PriceSource.entries.forEach { s ->
        Row(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = s == source, onClick = { onPick(s) })
            Column {
                Text(s.label)
                Text(help[s].orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Text(
        when {
            status.running -> "Getting TCGplayer's prices… ${status.done} of ${status.total} cards"
            status.tcgplayerAt == 0L -> "TCGplayer's prices download with the next price update (one card at a time, about a minute per 1,000 cards)."
            else -> "TCGplayer's prices last downloaded ${Fmt.dateTime(status.tcgplayerAt)}; they download once a day, with Cardmarket's."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (status.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    status.error?.let { Text("Last update: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}
