package com.poketrader.ui

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
fun SettingsScreen() {
    val c = LocalContext.current.container
    val priceType by c.settings.priceType.collectAsStateWithLifecycle()
    val tolerance by c.settings.tolerancePct.collectAsStateWithLifecycle()
    val lastFetch by c.settings.lastPriceFetch.collectAsStateWithLifecycle()
    val guideDate by c.settings.priceGuideDate.collectAsStateWithLifecycle()
    val count by c.prices.count.collectAsStateWithLifecycle(0)
    val state by c.prices.state.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Price used for valuing cards", style = MaterialTheme.typography.titleMedium)
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
            Text("Cardmarket price data", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Price guide date: ${formatGuideDate(guideDate)}")
            Text("Products with prices: $count")
            Text("Last downloaded: ${if (lastFetch == 0L) "never" else Fmt.dateTime(lastFetch)}")
            when (val s = state) {
                is PriceUpdateState.Running -> Text(s.message, color = MaterialTheme.colorScheme.primary)
                is PriceUpdateState.Failed -> Text("Last update failed: ${s.message}", color = MaterialTheme.colorScheme.error)
                PriceUpdateState.Idle -> {}
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { c.appScope.launch { c.prices.refresh() } },
                enabled = state !is PriceUpdateState.Running,
            ) { Text("Update prices now") }
            Spacer(Modifier.height(4.dp))
            Text(
                "Prices update automatically when you open the app and the data is older than 20 hours (about a 15 MB download). " +
                    "Cards already in a trade keep the price they had when added — use “Refresh prices” in a trade to update them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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

private fun formatGuideDate(raw: String?): String {
    if (raw == null) return "not downloaded yet"
    return try {
        val d = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(raw)
        if (d != null) Fmt.dateTime(d.time) else raw
    } catch (e: Exception) {
        raw
    }
}
