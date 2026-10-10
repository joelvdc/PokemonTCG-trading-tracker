package com.poketrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poketrader.data.CardRef
import com.poketrader.data.CollectionRow
import com.poketrader.data.PriceSource
import com.poketrader.data.PriceType
import com.poketrader.data.Pricing
import com.poketrader.data.ValueHistory
import kotlin.math.abs

/** One line of the price source comparison: a source's value for the cards and how many it prices. Since 1.16. */
data class SourceTotal(val source: PriceSource, val value: Double, val covered: Int, val copies: Int)

/** The cards' value at every source (Cardmarket's [type] price for cards a source has none for). */
fun sourceTotals(rows: List<CollectionRow>, type: PriceType): List<SourceTotal> {
    val copies = rows.sumOf { it.item.quantity }
    return PriceSource.entries.map { s ->
        SourceTotal(s, ValueHistory.sourceTotal(rows, s, type), if (s == PriceSource.CARDMARKET) copies else ValueHistory.covered(rows, s), copies)
    }
}

/** The sources side by side, each with a bar against the highest and how many cards it has a price for; tap one to pick it. */
@Composable
fun SourceComparison(totals: List<SourceTotal>, main: PriceSource, ratesKnown: Boolean, selected: PriceSource? = null, onPick: ((PriceSource) -> Unit)? = null) {
    val max = totals.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in totals) {
            val missing = t.source != PriceSource.CARDMARKET && t.covered == 0
            Column(Modifier.fillMaxWidth().then(if (onPick != null) Modifier.clickable { onPick(t.source) } else Modifier).padding(vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t.source.label + if (t.source == main) " · your price source" else "",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (t.source == selected) FontWeight.Bold else null, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(if (missing) "—" else Fmt.money(t.value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    if (!missing) {
                        Box(
                            Modifier.fillMaxWidth((t.value / max).toFloat().coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
                val note = when {
                    t.source != PriceSource.CARDMARKET && !ratesKnown -> "Waiting for the exchange rates"
                    missing -> "No prices yet: they download with the next price update"
                    t.covered < t.copies -> "${"%,d".format(t.covered)} of ${"%,d".format(t.copies)} cards priced; Cardmarket's price for the rest"
                    else -> null
                }
                note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** A card priced very differently in Europe (Cardmarket) and the US (TCGplayer). */
data class PriceGap(val row: CollectionRow, val cardmarket: Double, val tcgplayer: Double) {
    val difference get() = (tcgplayer - cardmarket) * row.item.quantity
}

/** The cards with the biggest gap between Cardmarket and TCGplayer, over the copies you own. Since 1.16. */
fun priceGaps(rows: List<CollectionRow>, type: PriceType, count: Int = 10): List<PriceGap> = rows.mapNotNull { r ->
    val cm = r.cardmarketPrice(type) ?: return@mapNotNull null
    val tcg = r.priceAt(PriceSource.TCGPLAYER, type) ?: return@mapNotNull null
    PriceGap(r, cm, tcg)
}.sortedByDescending { abs(it.difference) }.take(count)

@Composable
fun PriceGapList(gaps: List<PriceGap>) {
    if (gaps.isEmpty()) {
        Text("Nothing to compare yet: TCGplayer's prices download with the next price update.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column {
        for (g in gaps) {
            val item = g.row.item
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((if (item.quantity > 1) "${item.quantity}× " else "") + item.card.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${item.card.setName} · ${item.card.variantLabel} · Cardmarket ${Fmt.money(g.cardmarket)} · TCGplayer ${Fmt.money(g.tcgplayer)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    (if (g.difference >= 0) "US +" else "EU +") + Fmt.money(abs(g.difference)),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = if (g.difference >= 0) TrendColors.up else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** TCGplayer's price for this card version, with a link (English prints; others have none). Since 1.16. */
@Composable
fun OtherPrices(card: CardRef, onLink: (String) -> Unit) {
    val price = Pricing.at(PriceSource.TCGPLAYER, card, null)
    val url = Pricing.url(card)
    Row(
        Modifier.fillMaxWidth().then(if (url != null) Modifier.clickable { onLink(url) } else Modifier).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("TCGplayer", style = MaterialTheme.typography.bodyMedium)
            Text(
                when {
                    Pricing.usdPerEuro == null -> "Waiting for the exchange rates"
                    price == null && card.isJapanese -> "Japanese prints aren't on TCGplayer"
                    price == null -> "No price for this version"
                    else -> "Market price, any condition"
                },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(price?.let(Fmt::money) ?: "—", style = MaterialTheme.typography.bodyMedium, fontWeight = if (price != null) FontWeight.SemiBold else null)
        if (url != null) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open on TCGplayer", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** "≈" when the chosen price source has no price for the card, so Cardmarket's stands in. */
internal fun approx(row: CollectionRow) = if (row.isApprox) "≈" else ""
