package com.poketrader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poketrader.container
import com.poketrader.data.BinderChoice
import com.poketrader.data.CONDITIONS
import com.poketrader.data.CardRef
import com.poketrader.data.CollectionItem
import com.poketrader.data.CollectionRow
import com.poketrader.data.ImageKey
import com.poketrader.data.LANGUAGES
import com.poketrader.data.PriceAlerts
import com.poketrader.data.PriceType
import com.poketrader.data.WishAlert
import com.poketrader.data.WishlistItem
import com.poketrader.data.WishlistOwned
import com.poketrader.data.WishlistRow
import com.poketrader.data.levelEur

/** What the wishlist card window shows about alerts: the card's latest price and what it's compared with. Since 1.20. */
data class WishAlertInfo(
    val alert: WishAlert? = null,
    /** [PriceAlerts.basis] of the current settings. */
    val basis: String = "cardmarket:low",
)

/**
 * Edits a wishlist entry: how many, whether any version of the card will do, ordered and received
 * (since 1.20), a price alert (since 1.20), notes. Since 1.9.
 */
@Composable
fun WishlistDialog(
    row: WishlistRow,
    owned: WishlistOwned?,
    priceType: PriceType,
    alerts: WishAlertInfo,
    onDismiss: () -> Unit,
    onSave: (WishlistItem) -> Unit,
    onDelete: () -> Unit,
) {
    val card = row.item.card
    var item by remember { mutableStateOf(row.item) }
    val level = remember { MoneyInput.of(row.item.alertBelow, row.item.alertCurrency) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(card.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CardImage(
                        card.thumbUrl, Modifier.width(110.dp), enlargeUrl = card.largeUrl, placeholder = "#" + card.numberLabel,
                        fallbackKey = ImageKey(card.cardId, card.dataLang, card.variantId),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(card.setName, style = MaterialTheme.typography.bodyMedium)
                        if (row.item.isPending) {
                            Text("Added offline from Cardmarket's list" + (card.variantLabel.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("#${card.numberLabel} · ${card.variantLabel}", style = MaterialTheme.typography.bodySmall)
                        }
                        owned?.let {
                            Text(
                                if (it.owned > 0) "You have ${it.owned}" + (if (it.gotSince > 0) " (${it.gotSince} got since adding it)" else "") else "Not in your collection yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cards wanted", Modifier.weight(1f))
                    QuantityStepper(item.quantity, { item = item.copy(quantity = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Any version will do")
                        Text(
                            if (item.anyVariant) "Normal, reverse holo… all count" else "Only ${card.variantLabel.lowercase()} counts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // A card added offline has no version yet to keep to.
                    Switch(checked = item.anyVariant, onCheckedChange = { item = item.copy(anyVariant = it) }, enabled = !row.item.isPending)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ordered, on its way")
                        Text(
                            item.orderedAt?.let { "Ticked ${Fmt.date(it)}" } ?: "Tick it once you've bought it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Unticking and ticking again before saving keeps the date it was ticked on.
                    Checkbox(
                        checked = item.orderedAt != null,
                        onCheckedChange = { on -> item = item.copy(orderedAt = if (on) row.item.orderedAt ?: System.currentTimeMillis() else null) },
                    )
                }
                // Received cards move to the Received section; unticking puts the card back on the wishlist.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Received")
                        Text(
                            item.receivedAt?.let { "Ticked ${Fmt.date(it)}" } ?: "Tick it once it has arrived",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Checkbox(
                        checked = item.receivedAt != null,
                        onCheckedChange = { on -> item = item.copy(receivedAt = if (on) row.item.receivedAt ?: System.currentTimeMillis() else null) },
                    )
                }
                HorizontalDivider()
                PriceAlertFields(item, level, alerts) {
                    val typed = level.amount?.takeIf { it > 0 }
                    item = item.copy(alertBelow = typed, alertCurrency = typed?.let { level.code })
                }
                HorizontalDivider()
                OutlinedTextField(
                    value = item.notes ?: "",
                    onValueChange = { item = item.copy(notes = it.ifBlank { null }) },
                    label = { Text("Notes") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                PriceTable(pricesOf(card, row.price), priceType)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(item) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** The card window's "Price alert" part: the level, and the price it's compared with. Since 1.20. */
@Composable
internal fun PriceAlertFields(item: WishlistItem, level: MoneyInput, alerts: WishAlertInfo, onLevel: () -> Unit) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val a = alerts.alert
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Price alert", style = MaterialTheme.typography.titleSmall)
        MoneyField(level, "Notify me at or below", Modifier.fillMaxWidth(), placeholder = "No alert", onChange = onLevel)
        Text(
            "Compared with ${PriceAlerts.basisLong(alerts.basis)}" + (if (item.anyVariant) " (the cheapest version)" else "") + ". Leave it empty for no alert.",
            style = small, color = muted,
        )
        if (item.orderedAt != null && item.alertBelow != null) Text("Ordered cards don't send alerts.", style = small, color = muted)
        a?.price?.takeIf { a.basis == alerts.basis }?.let { p ->
            Text(
                "Now: ${PriceAlerts.basisLabel(alerts.basis)} ${if (item.anyVariant) "from " else ""}${Fmt.money(p)}" +
                    (a.variantLabel?.takeIf { item.anyVariant && it.isNotBlank() }?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        a?.checkedAt?.takeIf { it > 0 }?.let { Text("Checked ${Fmt.dateTime(it)}", style = small, color = muted) }
    }
}

/** What a wishlist row says under the card: which versions will do, whether it's ordered and whether you got it. */
fun wishLabel(item: WishlistItem, owned: WishlistOwned?): String? = listOfNotNull(
    if (item.isPending) "details follow when you're online" else if (item.anyVariant) null else "only this version",
    // A received card shows when it came instead of when it was ordered.
    item.receivedAt?.let { "✓ received ${Fmt.shortDate(it)}" + (item.receivedFrom?.let { f -> " · $f" } ?: "") }
        ?: item.orderedAt?.let { "📦 ordered ${Fmt.shortDate(it)}" },
    when {
        owned == null || item.receivedAt != null -> null
        owned.gotSince > 0 -> "✓ got ${owned.gotSince}"
        owned.owned > 0 -> "you have ${owned.owned}"
        else -> null
    },
).joinToString(" · ").ifEmpty { null }

/**
 * The wishlist row's alert line: the level (✓ once reached) and, for "any version" cards with a level,
 * the cheapest version's price. Null when there's nothing to say.
 */
fun wishPriceLine(item: WishlistItem, a: WishAlert?, basis: String): String? {
    // Received cards are a record now, not wanted: no prices to watch.
    if (item.receivedAt != null) return null
    val best = a?.price?.takeIf { a.basis == basis }
    val level = item.levelEur?.let { "🔔 ${Fmt.money(it)}" + if (best != null && best <= it + 0.000_1) " ✓" else "" }
    // Ordered cards aren't checked any more: only their level shows.
    if (item.orderedAt != null) return level
    val from = best?.takeIf { item.anyVariant && item.alertBelow != null }?.let { "${PriceAlerts.basisLabel(basis)} from ${Fmt.money(it)}" }
    return listOfNotNull(level, from).joinToString(" · ").ifEmpty { null }
}

/**
 * The line above the wishlist: cards wanted, how many of them are ordered (and on their way), and what
 * the rest still cost. Since 1.20.
 */
fun wishTotalLine(rows: List<CollectionRow>, isOrdered: (Long) -> Boolean, priceType: PriceType): String {
    val cards = rows.sumOf { it.item.quantity }
    val (ordered, rest) = rows.partition { isOrdered(it.item.id) }
    val toBuy = rest.sumOf { (it.unitPrice(priceType) ?: 0.0) * it.item.quantity }
    return if (ordered.isEmpty()) "$cards card(s) wanted · ${Fmt.money(toBuy)} to buy them"
    else "$cards card(s) wanted · ${ordered.sumOf { it.item.quantity }} ordered · ${Fmt.money(toBuy)} still to buy"
}

/** The wishlist as a plain list to share; ordered and received cards are left out, so they aren't bought twice. */
fun wishShareText(rows: List<WishlistRow>): String =
    rows.filter { it.item.orderedAt == null && it.item.receivedAt == null }
        .joinToString("\n") { "${it.item.quantity}× ${it.item.card.name}" + if (it.item.isPending) "" else " (${it.item.card.setName} #${it.item.card.numberLabel})" }

/** What "Add to the collection" fills in for a received wishlist card, before anything is changed. Since 1.20. */
data class WishToCollection(
    val card: CardRef?,
    val condition: String = "NM",
    val language: String = "EN",
    val quantity: Int,
    val binder: BinderChoice = BinderChoice(),
    val price: Double? = null,
    val currency: String? = null,
) {
    companion object {
        /** From the wishlist card: its version (none for a card added offline) and the copies wanted. */
        fun of(w: WishlistItem) = WishToCollection(
            card = w.card.takeIf { !w.isPending },
            language = if (w.card.isJapanese) "JA" else "EN",
            quantity = w.quantity,
        )
    }
}

/**
 * Moving a received wishlist card into the collection: the version (to confirm for "any version" cards,
 * to pick for cards added offline), condition, language, copies, binder and what was paid. When the
 * card's copies may be in the collection already ([gotSince] > 0), it warns and offers to just take the
 * card off the wishlist. Since 1.20.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddWishToCollectionDialog(
    item: WishlistItem,
    gotSince: Int,
    collection: List<CollectionItem>,
    onDismiss: () -> Unit,
    onTakeOff: () -> Unit,
    onAdd: (WishToCollection) -> Unit,
) {
    val c = LocalContext.current.container
    val binders = rememberBinders()
    var v by remember { mutableStateOf(WishToCollection.of(item)) }
    val priceInput = remember { MoneyInput.of(null, null) }
    var picking by remember { mutableStateOf(false) }
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // The card's versions (normal, reverse holo…), to pick the one you got.
    val versions by produceState<List<CardRef>?>(null, v.card?.cardId, v.card?.dataLang) {
        val shown = v.card
        value = if (shown == null) emptyList()
        else runCatching { c.tcgdex.card(shown.cardId, shown.dataLang)?.printings(shown.dataLang) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: listOf(shown)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${item.card.name} to the collection", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (gotSince > 0) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                "You've got $gotSince of this card since adding it to the wishlist. If that's this one, it's in the collection already.",
                                style = small,
                            )
                            TextButton(onClick = onTakeOff) { Text("Just take it off the wishlist") }
                        }
                    }
                }
                val card = v.card
                Column {
                    Text("Printing", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(card?.let { "${it.setName} · #${it.numberLabel}" } ?: "Choose the printing…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        when {
                            card == null -> "Added offline: pick the printing you got (needs internet)."
                            item.anyVariant -> "Any version was wanted: check the version below is the one you got."
                            else -> "The version on the wishlist."
                        },
                        style = small, color = muted,
                    )
                }
                val list = versions
                if (card != null && list == null) CircularProgressIndicator(Modifier.size(24.dp))
                if (card != null && list != null && list.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.forEach { p -> FilterChip(selected = p.variantId == card.variantId, onClick = { v = v.copy(card = p) }, label = { Text(p.variantLabel) }) }
                    }
                }
                DropdownSelector("Condition", v.condition, CONDITIONS.map { it.first }, { k -> CONDITIONS.first { it.first == k }.let { "${it.second} (${it.first})" } }, { v = v.copy(condition = it) }, Modifier.fillMaxWidth())
                DropdownSelector("Language", v.language, LANGUAGES.map { it.first }, { k -> LANGUAGES.firstOrNull { it.first == k }?.second ?: k }, { v = v.copy(language = it) }, Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("How many", Modifier.weight(1f))
                    QuantityStepper(v.quantity, { v = v.copy(quantity = it) })
                }
                BinderPicker("Put it in", v.binder, { v = v.copy(binder = it) })
                MoneyField(priceInput, "Paid per card (optional)", Modifier.fillMaxWidth()) {
                    v = v.copy(price = priceInput.amount?.takeIf { p -> p >= 0 }, currency = priceInput.code)
                }
                joiningStack(collection, v)?.let { s ->
                    Text(
                        "You have ${s.quantity} of this version in ${binderName(s.binderId, binders)} already: these join that stack" +
                            if (s.purchasePrice != null || v.price != null) ", and its purchase price becomes the average of all of them." else ".",
                        style = small, color = muted,
                    )
                }
                Text("The card then leaves the wishlist.", style = small, color = muted)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(v) }, enabled = v.card != null && v.binder.isValid) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (picking) {
        PrintingPickerDialog(v.card ?: item.card, onPick = { picked -> picking = false; v = v.copy(card = picked) }, onDismiss = { picking = false })
    }
}

/** The stack in [collection] the copies would join (same version, condition, language and binder); null if they'd make a new one. */
fun joiningStack(collection: List<CollectionItem>, v: WishToCollection): CollectionItem? {
    val card = v.card ?: return null
    if (v.binder.newName != null) return null
    return collection.firstOrNull {
        it.card.cardId == card.cardId && it.card.dataLang == card.dataLang && it.card.variantId == card.variantId &&
            it.condition == v.condition && it.language == v.language && it.binderId == v.binder.binderId
    }
}
