package com.poketrader.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.poketrader.container
import com.poketrader.data.Binder
import com.poketrader.data.CONDITIONS
import com.poketrader.data.CardLinks
import com.poketrader.data.CardRef
import com.poketrader.data.CardTarget
import com.poketrader.data.ImageKey
import com.poketrader.data.CollectionRow
import com.poketrader.data.LANGUAGES
import com.poketrader.data.LimitlessCards
import com.poketrader.data.Money
import com.poketrader.data.PriceEntity
import com.poketrader.data.PriceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Other copies shown before "Show all". */
private const val OTHERS_FOLDED = 3

private class Versions(val printings: List<CardRef>, val prices: Map<Int, PriceEntity>)

/**
 * A collection stack's card page: the card and its price, quantity, version, binder, condition,
 * language, purchase price and notes in a compact form, the other copies of the card you own (tap
 * one to open it), and Cardmarket's prices. Also opened from the value screen. [scope] must
 * outlive the page (the screen's), so saving isn't cancelled when it closes. Since 1.11.
 */
@Composable
fun CollectionCardDialog(row: CollectionRow, nav: NavController, snackbar: SnackbarHostState, scope: CoroutineScope, onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val priceType by c.settings.priceType.collectAsStateWithLifecycle()
    val binders = rememberBinders()
    val rows by remember(row.item.card.name) { c.db.collectionDao().observeByName(row.item.card.name) }.collectAsStateWithLifecycle(listOf(row))
    var currentId by remember { mutableLongStateOf(row.item.id) }
    val current = rows.firstOrNull { it.item.id == currentId } ?: row.takeIf { it.item.id == currentId } ?: rows.firstOrNull() ?: row
    val item = current.item
    fun initialFor(r: CollectionRow) = r.item.let { EditValues(it.quantity, it.condition, it.language, null, it.binderId, it.quantity, it.notes, it.purchasePrice) }
    var v by remember(item.id) { mutableStateOf(initialFor(current)) }
    var selected by remember(item.id) { mutableStateOf(item.card) }
    var paidText by remember(item.id) { mutableStateOf(Money.input(item.purchasePrice)) }
    var pricesOpen by remember { mutableStateOf(false) }
    var pickingPrinting by remember { mutableStateOf(false) }
    var switchTo by remember { mutableStateOf<CollectionRow?>(null) }
    val othersOpen by c.settings.cardOthersOpen.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    // The card's versions (normal, reverse holo…), from TCGdex; reloaded when another printing is picked.
    val versions by produceState<Versions?>(null, selected.cardId, selected.dataLang) {
        value = null
        val shown = selected
        val printings = runCatching { c.tcgdex.card(shown.cardId, shown.dataLang)?.printings(shown.dataLang) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: listOf(shown)
        value = Versions(printings, c.prices.pricesFor(printings.mapNotNull { it.cardmarketId }))
    }
    val prices = if (selected == item.card && current.price != null) pricesOf(selected, current.price) else pricesOf(selected, versions?.prices?.get(selected.cardmarketId))
    val now = com.poketrader.data.Pricing.unit(selected, prices.best(priceType))
    // The same card first (other binders, conditions, versions), then the rest by value.
    val others = rows.filter { it.item.id != item.id }.sortedWith(
        compareBy<CollectionRow> { if (it.item.card.cardId == item.card.cardId) 0 else 1 }
            .thenByDescending { it.unitPrice(priceType) ?: 0.0 }
            .thenBy { it.item.card.setName },
    )
    val dirty = v != initialFor(current) || selected != item.card

    fun save(after: () -> Unit = {}) {
        val values = v
        val card = selected
        scope.launch {
            c.repo.saveCollectionEdit(
                item.copy(
                    card = card, quantity = values.quantity, condition = values.condition, language = values.language,
                    notes = values.notes?.trim()?.ifEmpty { null }, purchasePrice = values.purchasePrice,
                ),
                values.binderId, values.move,
            )
            after()
        }
    }
    fun open(other: CollectionRow) {
        if (dirty) switchTo = other else currentId = other.item.id
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp),
        ) {
            Column(Modifier.padding(top = 16.dp)) {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // The card: picture, name, printing, price, and its two actions.
                    Row {
                        CardImage(
                            selected.thumbUrl, Modifier.width(84.dp), enlargeUrl = selected.largeUrl, placeholder = "#" + selected.numberLabel,
                            fallbackKey = ImageKey(selected.cardId, selected.dataLang, selected.variantId),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(selected.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("#${selected.numberLabel} · ${selected.rarity}", style = MaterialTheme.typography.bodySmall)
                            Text(selected.setName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Fmt.money(now), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(6.dp))
                                TrendBadge(prices.trendChange)
                                if (v.quantity > 1 && now != null) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("· ${Fmt.money(now * v.quantity)} for ${v.quantity}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            val notes = listOfNotNull(
                                "Japanese print".takeIf { selected.isJapanese },
                                "Oversized (jumbo) card".takeIf { selected.oversized },
                            )
                            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallAction("Printing", Icons.Default.SwapHoriz) { pickingPrinting = true }
                        SmallAction("Other card", Icons.Default.Search) {
                            onDismiss()
                            nav.openSearch(CardTarget.ReplaceCollectionItem(item.id), item.card.name)
                        }
                        SmallAction("Cardmarket", Icons.AutoMirrored.Filled.OpenInNew) { runCatching { uriHandler.openUri(CardLinks.cardmarket(selected)) } }
                    }
                    if (selected.variantId == LimitlessCards.VARIANT) {
                        Text(
                            "Not in TCGdex yet: picture and details from Limitless TCG, and the price is the international print's.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selected.firstEdition) {
                        Text(
                            "Cardmarket doesn't price 1st Edition separately, so this price may be too low.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    // What you have: compact fields, two to a row.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CompactBox("Quantity", Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StepButton(Icons.Default.Remove, "Less", v.quantity > 1) { v = v.copy(quantity = v.quantity - 1, move = minOf(v.move, v.quantity - 1).coerceAtLeast(1)) }
                                Text("${v.quantity}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
                                StepButton(Icons.Default.Add, "More", true) { v = v.copy(quantity = v.quantity + 1) }
                            }
                        }
                        val options = versions?.printings.orEmpty()
                        if (options.size > 1) {
                            CompactDropdown(
                                "Version", selected, options, { it.variantLabel }, { selected = it }, Modifier.weight(1f),
                                menuLabel = { p -> "${p.variantLabel}  ${Fmt.money(com.poketrader.data.Pricing.unit(p, pricesOf(p, versions?.prices?.get(p.cardmarketId)).best(priceType)))}" },
                            )
                        } else {
                            CompactBox("Version", Modifier.weight(1f)) {
                                Text(selected.variantLabel, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    CompactDropdown(
                        "Binder", v.binderId, listOf(Binder.UNSORTED) + binders.map { it.id }, { binderName(it, binders) },
                        { v = v.copy(binderId = it, move = v.quantity) }, Modifier.fillMaxWidth(),
                    )
                    if (v.binderId != item.binderId && v.quantity > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Cards to move", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            StepButton(Icons.Default.Remove, "Less", v.move > 1) { v = v.copy(move = v.move - 1) }
                            Text("${v.move.coerceIn(1, v.quantity)}", Modifier.width(32.dp), textAlign = TextAlign.Center)
                            StepButton(Icons.Default.Add, "More", v.move < v.quantity) { v = v.copy(move = v.move + 1) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactDropdown(
                            "Condition", v.condition, CONDITIONS.map { it.first }, { cd -> CONDITIONS.first { it.first == cd }.second },
                            { v = v.copy(condition = it) }, Modifier.weight(1f), menuLabel = { cd -> "$cd · " + CONDITIONS.first { it.first == cd }.second },
                        )
                        CompactDropdown(
                            "Language", v.language, LANGUAGES.map { it.first }, { l -> LANGUAGES.firstOrNull { it.first == l }?.second ?: l },
                            { v = v.copy(language = it) }, Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactTextField(
                            "Paid per card", paidText, {
                                paidText = it
                                v = v.copy(purchasePrice = Fmt.parseMoneyEur(it))
                            },
                            Modifier.weight(0.38f), placeholder = Money.symbol, keyboardType = KeyboardType.Decimal,
                        )
                        CompactTextField("Notes", v.notes ?: "", { v = v.copy(notes = it.ifBlank { null }) }, Modifier.weight(0.62f), placeholder = "Where from, condition…", singleLine = false)
                    }
                    val paid = v.purchasePrice
                    if (paid != null && paid > 0 && now != null) {
                        val diff = (now - paid) * v.quantity
                        Text(
                            "Paid ${Fmt.money(paid * v.quantity)} · now ${Fmt.money(now * v.quantity)} · " +
                                (if (diff >= 0) "+" else "−") + Fmt.money(kotlin.math.abs(diff)) + " (%+.0f%%)".format((now - paid) / paid * 100),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (diff >= 0) TrendColors.up else TrendColors.down,
                        )
                    }

                    // Every other copy you own: other sets, versions, conditions and binders.
                    if (others.isNotEmpty()) {
                        HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().clickable { c.settings.setCardOthersOpen(!othersOpen) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("You also own ${others.sumOf { it.item.quantity }} more ${item.card.name}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Tap a card to open it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(if (othersOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (othersOpen) "Show fewer" else "Show all")
                        }
                        Column {
                            (if (othersOpen) others else others.take(OTHERS_FOLDED)).forEach { o ->
                                OtherCopyRow(o, priceType, binderName(o.item.binderId, binders), sameCard = o.item.card.cardId == item.card.cardId) { open(o) }
                            }
                        }
                        if (others.size > OTHERS_FOLDED) {
                            TextButton(onClick = { c.settings.setCardOthersOpen(!othersOpen) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                Text(if (othersOpen) "Show fewer" else "Show all ${others.size}")
                            }
                        }
                    }

                    // TCGplayer (since 1.16).
                    HorizontalDivider()
                    Text("Prices elsewhere", style = MaterialTheme.typography.titleSmall)
                    OtherPrices(selected) { url -> runCatching { uriHandler.openUri(url) } }

                    // Cardmarket's numbers, folded away.
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().clickable { pricesOpen = !pricesOpen }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Cardmarket prices", style = MaterialTheme.typography.titleSmall)
                            if (!pricesOpen) {
                                Text(
                                    "Trend ${Fmt.money(prices.trend)} · Avg ${Fmt.money(prices.avg)} · 30 days ${Fmt.money(prices.avg30)} · Low ${Fmt.money(prices.low)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Icon(if (pricesOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (pricesOpen) "Fewer prices" else "All prices")
                    }
                    if (pricesOpen) PriceTable(prices, priceType, title = false)
                    Spacer(Modifier.width(1.dp))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        onDismiss()
                        scope.launch {
                            c.repo.deleteCollectionItem(item.id)
                            if (snackbar.showUndo("${item.card.name} removed")) c.repo.restoreCollectionItem(item)
                        }
                    }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onDismiss(); save() }, enabled = dirty) { Text("Save") }
                }
            }
        }
    }

    if (pickingPrinting) {
        PrintingPickerDialog(selected, onPick = { selected = it; pickingPrinting = false }, onDismiss = { pickingPrinting = false })
    }
    switchTo?.let { other ->
        AlertDialog(
            onDismissRequest = { switchTo = null },
            title = { Text("Save your changes?") },
            text = { Text("You changed this ${item.card.name}. Save before opening the other one?") },
            confirmButton = {
                TextButton(onClick = {
                    switchTo = null
                    save { currentId = other.item.id }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { switchTo = null; currentId = other.item.id }) { Text("Discard") }
                    TextButton(onClick = { switchTo = null }) { Text("Cancel") }
                }
            },
        )
    }
}

/** One other copy: set and number, version, condition, language, binder, how many and their value. */
@Composable
private fun OtherCopyRow(row: CollectionRow, priceType: PriceType, binder: String, sameCard: Boolean, onClick: () -> Unit) {
    val item = row.item
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        CardImage(item.card.thumbUrl, Modifier.width(32.dp), placeholder = "#" + item.card.numberLabel, fallbackKey = ImageKey(item.card.cardId, item.card.dataLang, item.card.variantId))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${item.card.setName} #${item.card.numberLabel}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                variantBadge(item.card)?.let { Tag(it) }
                Tag(item.condition)
                if (item.language != "EN") Tag(item.language)
            }
            Text(
                if (sameCard) "$binder · same card" else binder,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${item.quantity}×", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(Fmt.money(row.unitPrice(priceType)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) { Icon(icon, description, Modifier.size(18.dp)) }
}

/** A small outlined field: its label on top, the value below. */
@Composable
private fun CompactBox(label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .heightIn(min = 52.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
        trailing?.invoke()
    }
}

@Composable
private fun <T> CompactDropdown(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    menuLabel: (T) -> String = optionLabel,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        CompactBox(label, Modifier.fillMaxWidth(), onClick = { open = true }, trailing = { Icon(Icons.Default.ArrowDropDown, null) }) {
            Text(optionLabel(selected), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(menuLabel(o), fontWeight = if (o == selected) FontWeight.Bold else null) },
                    onClick = { open = false; onSelect(o) },
                )
            }
        }
    }
}

@Composable
private fun CompactTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    CompactBox(label, modifier) {
        Box {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 4,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            )
        }
    }
}
