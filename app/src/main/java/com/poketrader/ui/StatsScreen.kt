package com.poketrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.poketrader.container
import com.poketrader.data.Binder
import com.poketrader.data.CollectionJump
import com.poketrader.data.CollectionRow
import com.poketrader.data.CollectionStats
import com.poketrader.data.CollectionStatsResult
import com.poketrader.data.Era
import com.poketrader.data.PriceType
import com.poketrader.data.StatCard
import com.poketrader.data.StatEntry
import com.poketrader.data.AppCurrency
import com.poketrader.data.Money
import com.poketrader.data.StatMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Collection statistics: rarity, set completion, eras, special Pokémon, prices and more. Since 1.13. */
@Composable
fun StatsScreen(nav: NavController) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val priceType by c.settings.priceType.collectAsStateWithLifecycle()
    val rows by remember { c.db.collectionDao().observeAll() }.collectAsStateWithLifecycle(null)
    val binders = rememberBinders()
    var binder by rememberSaveable { mutableStateOf<Long?>(null) }
    var mode by rememberSaveable { mutableStateOf(StatMode.CARDS) }
    var opened by remember { mutableStateOf<CollectionRow?>(null) }

    // Series of the international and Japanese sets; an empty map (all "Other") until loaded or when offline.
    val eras by produceState(emptyMap<String, Era>()) {
        value = listOf("en", "ja").flatMap { lang -> c.sets.eras(lang).map { (id, e) -> "$lang/$id" to e } }.toMap()
    }
    val stats by produceState<CollectionStatsResult?>(null, rows, binder, priceType, binders, eras) {
        val all = rows ?: return@produceState
        value = withContext(Dispatchers.Default) {
            CollectionStats.compute(
                rows = if (binder == null) all else all.filter { it.item.binderId == binder },
                priceType = priceType,
                binderNames = binders.associate { it.id to it.name },
                eras = eras,
                binder = binder,
            )
        }
    }
    fun show(jump: CollectionJump?) {
        jump ?: return
        c.collectionJump.value = jump
        nav.safePopBackStack()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Collection stats") },
                navigationIcon = { IconButton(onClick = { nav.safePopBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        val s = stats
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        StatsContent(
            s, priceType, binders, binder, { binder = it }, mode, { mode = it }, erasLoaded = eras.isNotEmpty(),
            onPick = ::show, onOpen = { opened = it }, modifier = Modifier.padding(pad),
        )
    }

    opened?.let { row -> CollectionCardDialog(row, nav, snackbar, scope) { opened = null } }
}

/** The stats themselves, drawn from [s]; split from [StatsScreen] so screenshot tests can show it. Since 1.14. */
@Composable
fun StatsContent(
    s: CollectionStatsResult,
    priceType: PriceType,
    binders: List<Binder>,
    binder: Long?,
    onBinder: (Long?) -> Unit,
    mode: StatMode,
    onMode: (StatMode) -> Unit,
    erasLoaded: Boolean,
    onPick: (CollectionJump?) -> Unit,
    onOpen: (CollectionRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val show = onPick
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "binders") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = binder == null, onClick = { onBinder(null) }, label = { Text("All cards") }) }
                item { FilterChip(selected = binder == Binder.UNSORTED, onClick = { onBinder(Binder.UNSORTED) }, label = { Text(Binder.UNSORTED_NAME) }) }
                items(binders, key = { it.id }) { b -> FilterChip(selected = binder == b.id, onClick = { onBinder(b.id) }, label = { Text(b.name) }) }
            }
        }
        item(key = "overview") { Overview(s, priceType) }
        if (s.copies == 0) return@LazyColumn
        item(key = "mode") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                StatMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(selected = mode == m, onClick = { onMode(m) }, shape = SegmentedButtonDefaults.itemShape(i, StatMode.entries.size)) {
                        Text("Charts by ${m.label.lowercase()}")
                    }
                }
            }
        }
        item(key = "rarity") {
            Section("Rarity", "Rarest first. Tap a line to see those cards.") { Bars(s.rarities, mode, keepOrder = true, limit = 8, onPick = show) }
        }
        item(key = "completion") {
            Section("Set completion", "Different card numbers you own out of the set's official count; \"+2\" are secret rares beyond it.") {
                Completion(s.completion, limit = 8, onPick = show)
            }
        }
        item(key = "eras") {
            Section("Era", if (!erasLoaded) "The list of eras couldn't be loaded yet (it needs the internet once)." else "The series each card's set belongs to, oldest first.") {
                Bars(s.eras, mode, keepOrder = true, onPick = show)
            }
        }
        if (s.kinds.isNotEmpty()) {
            item(key = "kinds") { Section("Special Pokémon", "ex, V, GX, Mega and the like, read from the card names.") { Bars(s.kinds, mode) {} } }
        }
        item(key = "pokemon") {
            Section("Most collected Pokémon", "By name: Pikachu ex and Pikachu V count as Pikachu. Energy is left out.") {
                Bars(s.pokemon, mode, limit = 10, onPick = show)
            }
        }
        item(key = "prices") { Section("Price of one card", "Cards without a price aren't counted.") { Bars(s.priceRanges, mode, keepOrder = true, onPick = show) } }
        item(key = "sets") { Section("Top sets") { Bars(s.topSets, mode, limit = 8, onPick = show) } }
        item(key = "versions") { Section("Version", "Normal, holo, reverse holo, special foils, stamps…") { Bars(s.versions, mode, limit = 8, onPick = show) } }
        if (s.prints.size > 1) item(key = "prints") { Section("Print") { Bars(s.prints, mode, keepOrder = true, onPick = show) } }
        if (s.languages.size > 1) item(key = "languages") { Section("Language") { Bars(s.languages, mode, limit = 6, onPick = show) } }
        item(key = "conditions") { Section("Condition") { Bars(s.conditions, mode, keepOrder = true, onPick = show) } }
        if (binder == null && s.binders.size > 1) item(key = "binderValue") { Section("Binders") { Bars(s.binders, mode, onPick = show) } }
        item(key = "added") { Section("Cards added", "The last 12 months." + if (mode == StatMode.VALUE && Money.display.currency != AppCurrency.EUR) " Values in ${Money.symbol}" else "") { Columns(s.added, mode) } }
        item(key = "valuable") {
            Section("Most valuable", "Tap a card to open it.") { CardList(s.mostValuable, priceType, onOpen) }
        }
    }
}

@Composable
private fun Overview(s: CollectionStatsResult, priceType: PriceType) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                Figure("Cards", "%,d".format(s.copies), Modifier.weight(1f))
                Figure("Different", "%,d".format(s.uniqueNames), Modifier.weight(1f))
                Figure("Printings", "%,d".format(s.printings), Modifier.weight(1f))
            }
            Row {
                Figure("Value (${priceType.short})", Fmt.money(s.value), Modifier.weight(1f))
                Figure("Per card", Fmt.money(s.averageValue), Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
            if (s.paidCopies > 0) {
                val diff = s.paidNowWorth - s.paid
                Text(
                    "${s.paidCopies} card(s) with a purchase price: paid ${Fmt.money(s.paid)}, worth ${Fmt.money(s.paidNowWorth)} now (${Fmt.signedMoney(diff)})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (s.unpriced > 0) {
                Text("${s.unpriced} card(s) without a price", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            content()
        }
    }
}

private fun amountText(e: StatEntry, mode: StatMode) = if (mode == StatMode.CARDS) "%,d".format(e.copies) else Fmt.money(e.value)

@Composable
private fun Bar(fraction: Double) {
    Box(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))) {
        Box(
            Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0.01f, 1f)).fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
        )
    }
}

/** Horizontal bars, longest first (or in the given order); tapping one shows those cards. */
@Composable
private fun Bars(entries: List<StatEntry>, mode: StatMode, keepOrder: Boolean = false, limit: Int = Int.MAX_VALUE, onPick: (CollectionJump?) -> Unit) {
    val shownEntries = entries.filter { it.copies > 0 }
    if (shownEntries.isEmpty()) {
        Text("Nothing to show.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val max = shownEntries.maxOf { it.amount(mode) }.takeIf { it > 0 } ?: 1.0
    val sorted = if (keepOrder) shownEntries else shownEntries.sortedByDescending { it.amount(mode) }
    var all by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (if (all) sorted else sorted.take(limit)).forEach { e ->
            Column(Modifier.fillMaxWidth().clickable(enabled = e.jump != null) { onPick(e.jump) }) {
                Row {
                    Text(e.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(amountText(e, mode), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Bar(e.amount(mode) / max)
            }
        }
        ShowAllButton(sorted.size, limit, all) { all = !all }
    }
}

/** Set completion: one bar per set, filled to the share of its official cards owned. */
@Composable
private fun Completion(entries: List<StatEntry>, limit: Int, onPick: (CollectionJump?) -> Unit) {
    if (entries.isEmpty()) {
        Text("Nothing to show.", style = MaterialTheme.typography.bodySmall)
        return
    }
    var all by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (if (all) entries else entries.take(limit)).forEach { e ->
            Column(Modifier.fillMaxWidth().clickable { onPick(e.jump) }) {
                Row {
                    Text(e.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${e.detail} · ${"%.0f".format((e.fraction ?: 0.0) * 100)}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Bar(e.fraction ?: 0.0)
            }
        }
        ShowAllButton(entries.size, limit, all) { all = !all }
    }
}

/** "Show all 32" / "Show fewer" under a list cut at [limit]; nothing when the list is short enough. */
@Composable
private fun ShowAllButton(size: Int, limit: Int, all: Boolean, onToggle: () -> Unit) {
    if (size <= limit) return
    TextButton(onClick = onToggle) { Text(if (all) "Show fewer" else "Show all $size") }
}

/** Vertical bars (cards added per month), labelled underneath. */
@Composable
private fun Columns(entries: List<StatEntry>, mode: StatMode) {
    val max = entries.maxOfOrNull { it.amount(mode) }?.takeIf { it > 0 } ?: 1.0
    Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        entries.forEach { e ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                if (e.copies > 0) {
                    Text(
                        if (mode == StatMode.CARDS) "${e.copies}" else Money.column(e.value),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    )
                }
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight((e.amount(mode) / max).toFloat().coerceIn(0.01f, 0.75f))
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                )
                Text(e.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun CardList(cards: List<StatCard>, priceType: PriceType, onOpen: (CollectionRow) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        cards.forEach { sc ->
            val card = sc.row.item.card
            Row(Modifier.fillMaxWidth().clickable { onOpen(sc.row) }, verticalAlignment = Alignment.CenterVertically) {
                CardImage(card.thumbUrl, Modifier.width(34.dp), enlargeUrl = card.largeUrl)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(card.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${card.setName} · ${card.numberLabel}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Fmt.money(sc.row.unitPrice(priceType)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(sc.note, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
