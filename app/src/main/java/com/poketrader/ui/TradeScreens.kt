package com.poketrader.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.poketrader.container
import com.poketrader.data.Binder
import com.poketrader.data.BinderChoice
import com.poketrader.data.CardTarget
import com.poketrader.data.ImageKey
import com.poketrader.data.PriceType
import com.poketrader.data.Side
import com.poketrader.data.TradeItem
import com.poketrader.data.TradeWithItems
import com.poketrader.data.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Trades have no number the user cares about; they're known by who and when. */
fun tradeTitle(t: TradeWithItems) =
    if (t.trade.partner.isBlank()) "Trade · ${Fmt.dateTime(t.trade.createdAt)}" else "Trade with ${t.trade.partner}"

// ---- Trade list ----------------------------------------------------------------------------

@Composable
fun TradesListScreen(nav: NavController) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val trades by remember { c.db.tradeDao().observeAll() }.collectAsStateWithLifecycle(null)
    val priceType by c.settings.priceType.collectAsStateWithLifecycle()
    val tolerance by c.settings.tolerancePct.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        c.repo.deleteEmptyDrafts()
        // A trade was just deleted on its own screen: offer to bring it back.
        val deleted = c.deletedTrade ?: return@LaunchedEffect
        c.deletedTrade = null
        if (snackbar.showUndo("Trade deleted")) c.repo.restoreTrade(deleted)
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val csv = c.repo.exportTradesCsv(priceType)
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } }
            Toast.makeText(context, "Trades exported", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("My trades") },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Export trades (CSV)") },
                            leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                            onClick = { menu = false; exporter.launch("pokemon-trades.csv") },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { scope.launch { nav.navigate("trade/${c.repo.newTrade()}") } },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New trade", fontSize = 18.sp) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val list = trades
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState(
                "🤝",
                "No trades yet",
                "Tap “New trade”. Then scan the cards you get and the cards you give — the app tells you if the trade is fair!",
                Modifier.padding(pad),
            )
            else -> LazyColumn(
                Modifier.padding(pad),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.trade.id }) { t ->
                    TradeCard(t, priceType, tolerance) { nav.navigate("trade/${t.trade.id}") }
                }
            }
        }
    }
}

@Composable
private fun TradeCard(t: TradeWithItems, priceType: PriceType, tolerance: Int, onClick: () -> Unit) {
    val b = t.balance(priceType, tolerance)
    val emoji = when (b.verdict) {
        Verdict.FAIR -> "👍"
        Verdict.FAVORS_YOU -> "🎉"
        Verdict.FAVORS_THEM -> "⚠️"
        Verdict.EMPTY -> "🃏"
    }
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 28.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(tradeTitle(t), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Give ${Fmt.money(b.give)} · Get ${Fmt.money(b.get)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (t.trade.applied) Tag("✓ Done") else Tag("Open", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            }
            if (t.items.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    t.get.take(4).forEach { CardImage(it.card.thumbUrl, Modifier.width(40.dp), fallbackKey = ImageKey(it.card.cardId, it.card.dataLang, it.card.variantId)) }
                    if (t.get.isNotEmpty() && t.give.isNotEmpty()) Text("  ⇄  ", style = MaterialTheme.typography.titleMedium)
                    t.give.take(4).forEach { CardImage(it.card.thumbUrl, Modifier.width(40.dp), fallbackKey = ImageKey(it.card.cardId, it.card.dataLang, it.card.variantId)) }
                }
            }
        }
    }
}

// ---- Trade editor --------------------------------------------------------------------------

@Composable
fun TradeEditorScreen(nav: NavController, tradeId: Long) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val data by remember(tradeId) { c.db.tradeDao().observe(tradeId) }.collectAsStateWithLifecycle(null)
    val owned by remember { c.db.collectionDao().observeOwned() }.collectAsStateWithLifecycle(emptyList())
    val ownedMap = remember(owned) { owned.associate { it.cardId to it.qty } }
    val wishRows by remember { c.db.wishlistDao().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val wanted = remember(wishRows) { wishRows.map { it.item.card.cardId }.toSet() }
    val priceType by c.settings.priceType.collectAsStateWithLifecycle()
    val tolerance by c.settings.tolerancePct.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var partner by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(data != null) {
        data?.let {
            if (partner == null) partner = it.trade.partner
            if (notes == null) notes = it.trade.notes
        }
    }
    var editing by remember { mutableStateOf<TradeItem?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmApply by remember { mutableStateOf(false) }

    val t = data
    val applied = t?.trade?.applied == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (partner.isNullOrBlank()) "Trade" else "Trade with $partner", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (t != null) Text(Fmt.dateTime(t.trade.createdAt), style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        t?.let {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, tradeSummary(it, priceType, tolerance))
                            }
                            context.startActivity(Intent.createChooser(send, "Share trade"))
                        }
                    }) { Icon(Icons.Default.Share, "Share") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Refresh prices") },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            enabled = !applied,
                            onClick = {
                                menu = false
                                scope.launch {
                                    c.repo.refreshTradePrices(tradeId)
                                    snackbar.showSnackbar("Prices updated")
                                }
                            },
                        )
                        DropdownMenuItem(text = { Text("Delete trade") }, onClick = { menu = false; confirmDelete = true })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (t != null) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (applied) {
                            Icon(Icons.Default.CheckCircle, null, tint = VerdictColors.fair)
                            Spacer(Modifier.width(8.dp))
                            Text("Trade done — cards moved in “My cards”", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = {
                                scope.launch {
                                    c.repo.revertTrade(tradeId)
                                    snackbar.showSnackbar("Undone — you can change the trade again")
                                }
                            }) {
                                Icon(Icons.AutoMirrored.Filled.Undo, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Undo")
                            }
                        } else {
                            Button(
                                onClick = { confirmApply = true },
                                enabled = t.items.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) { Text("✓  We traded!", fontSize = 18.sp) }
                        }
                    }
                }
            }
        },
    ) { pad ->
        if (t == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val balance = t.balance(priceType, tolerance)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.padding(pad),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { VerdictBanner(balance, Modifier.fillMaxWidth()) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = partner ?: "",
                    onValueChange = { v -> partner = v; scope.launch { c.repo.setPartner(tradeId, v) } },
                    label = { Text("Trading with (friend's name)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            tradeSide(
                title = "I get", side = Side.GET, items = t.get, priceType = priceType, ownedMap = null, locked = applied, wanted = wanted,
                onScan = { nav.openScanner(CardTarget.TradeSide(tradeId, Side.GET)) },
                onSearch = { nav.openSearch(CardTarget.TradeSide(tradeId, Side.GET)) },
                onEdit = { editing = it },
            )
            tradeSide(
                title = "I give", side = Side.GIVE, items = t.give, priceType = priceType, ownedMap = ownedMap, locked = applied,
                onScan = { nav.openScanner(CardTarget.TradeSide(tradeId, Side.GIVE)) },
                onSearch = { nav.openSearch(CardTarget.TradeSide(tradeId, Side.GIVE)) },
                onEdit = { editing = it },
            )
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = notes ?: "",
                    onValueChange = { v -> notes = v; scope.launch { c.repo.setNotes(tradeId, v) } },
                    label = { Text("Notes") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        }
    }

    editing?.let { item ->
        CardDialog(
            card = item.card,
            initial = EditValues(item.quantity, item.condition, item.language, item.customPrice),
            priceType = priceType,
            confirmLabel = "Save",
            allowCustomPrice = true,
            enabled = !applied,
            onDismiss = { editing = null },
            onConfirm = { card, v ->
                editing = null
                scope.launch {
                    c.repo.updateTradeItem(
                        item.copy(card = card, quantity = v.quantity, condition = v.condition, language = v.language, customPrice = v.customPrice)
                    )
                }
            },
            onDelete = {
                editing = null
                scope.launch {
                    c.repo.deleteTradeItem(item.id)
                    if (snackbar.showUndo("${item.card.name} removed")) c.repo.restoreTradeItem(item)
                }
            },
            onChangeCard = {
                editing = null
                nav.openSearch(CardTarget.ReplaceTradeItem(item.id), item.card.name)
            },
        )
    }

    if (confirmApply && t != null) {
        val getN = t.get.sumOf { it.quantity }
        val giveN = t.give.sumOf { it.quantity }
        var binder by remember { mutableStateOf(BinderChoice()) }
        AlertDialog(
            onDismissRequest = { confirmApply = false },
            title = { Text("Did you swap the cards?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "The $getN card(s) you got go into “My cards”, and the $giveN card(s) you gave are taken out " +
                            "(from ${Binder.UNSORTED_NAME} first).\n\nYou can undo this later."
                    )
                    if (getN > 0) {
                        BinderPicker(
                            label = "Put the new cards in",
                            choice = binder,
                            onChange = { binder = it },
                            suggestedName = if (t.trade.partner.isBlank()) "Trade ${Fmt.date(t.trade.createdAt)}" else "Trade with ${t.trade.partner}",
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = binder.isValid, onClick = {
                    confirmApply = false
                    scope.launch {
                        val missing = c.repo.applyTrade(tradeId, c.repo.resolve(binder))
                        snackbar.showSnackbar(
                            if (missing == 0) "Done! “My cards” is updated"
                            else "Done! ($missing given card(s) weren't in “My cards”)"
                        )
                    }
                }) { Text("Yes, we traded") }
            },
            dismissButton = { TextButton(onClick = { confirmApply = false }) { Text("Not yet") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this trade?") },
            text = {
                Text(
                    if (applied) "The trade is removed from the list. “My cards” stays as it is now (use Undo first to reverse the card changes)."
                    else "The trade and its cards will be removed."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        // The trade list shows the Undo message, since this screen closes.
                        c.deletedTrade = c.db.tradeDao().get(tradeId)
                        c.repo.deleteTrade(tradeId)
                        nav.popBackStack()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun LazyGridScope.tradeSide(
    title: String,
    side: String,
    items: List<TradeItem>,
    priceType: PriceType,
    ownedMap: Map<String, Int>?,
    locked: Boolean,
    onScan: () -> Unit,
    onSearch: () -> Unit,
    onEdit: (TradeItem) -> Unit,
    wanted: Set<String> = emptySet(),
) {
    item(key = "header-$side", span = { GridItemSpan(maxLineSpan) }) {
        Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    Fmt.money(items.sumOf { it.lineTotal(priceType) }),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (!locked) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Button(onClick = onScan, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Default.CameraAlt, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Scan", fontSize = 17.sp)
                    }
                    FilledTonalButton(onClick = onSearch, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Search", fontSize = 17.sp)
                    }
                }
            }
        }
    }
    if (items.isEmpty()) {
        item(key = "empty-$side", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "No cards yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
    items(items, key = { it.id }) { item ->
        val owned = ownedMap?.let { it[item.card.cardId] ?: 0 }
        CardTile(
            card = item.card,
            price = item.unitPrice(priceType),
            quantity = item.quantity,
            language = item.language,
            footnote = when {
                item.card.cardId in wanted -> "★ on your wishlist"
                owned == null -> null
                owned >= item.quantity -> "✓ in My cards"
                else -> "✗ not in My cards"
            },
            footnoteIsWarning = owned != null && owned < item.quantity,
            trend = item.prices.trendChange,
        ) { onEdit(item) }
    }
}

private fun tradeSummary(t: TradeWithItems, type: PriceType, tolerance: Int): String {
    val b = t.balance(type, tolerance)
    fun lines(items: List<TradeItem>) = items.joinToString("\n") { i ->
        "  ${i.quantity}× ${i.card.name} (${i.card.setName} #${i.card.numberLabel}, ${i.card.variantLabel}) — ${Fmt.money(i.lineTotal(type))}"
    }
    return buildString {
        appendLine(if (t.trade.partner.isBlank()) "Pokémon trade — ${Fmt.date(t.trade.createdAt)}" else "Pokémon trade with ${t.trade.partner} — ${Fmt.date(t.trade.createdAt)}")
        appendLine()
        appendLine("I give (${Fmt.money(b.give)}):")
        appendLine(lines(t.give).ifEmpty { "  —" })
        appendLine()
        appendLine("I get (${Fmt.money(b.get)}):")
        appendLine(lines(t.get).ifEmpty { "  —" })
        appendLine()
        append("Difference: ${Fmt.signedMoney(b.diff)} (Cardmarket ${type.label.lowercase()})")
    }
}
