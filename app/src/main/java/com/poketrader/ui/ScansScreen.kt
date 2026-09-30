package com.poketrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.poketrader.container
import com.poketrader.data.BinderChoice
import com.poketrader.data.CardTarget
import com.poketrader.data.ScanRow
import com.poketrader.data.Side
import kotlinx.coroutines.launch

private enum class ScanAction { BINDER, TRADE }

/**
 * The Scan tab: cards scanned here wait until they're sent to a binder or a trade, or discarded.
 */
@Composable
fun ScansScreen(nav: NavController) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val rows by remember { c.db.scanDao().observeAll() }.collectAsStateWithLifecycle(null)
    val priceType by c.settings.priceType.collectAsStateWithLifecycle()
    var unselected by remember { mutableStateOf(setOf<Long>()) }
    var action by remember { mutableStateOf<ScanAction?>(null) }
    var editing by remember { mutableStateOf<ScanRow?>(null) }

    val all = rows
    val selectedIds = all?.map { it.item.id }?.filterNot { it in unselected }.orEmpty()
    val selectedCount = all?.filter { it.item.id in selectedIds }?.sumOf { it.item.quantity } ?: 0

    fun report(message: String, undo: (suspend () -> Unit)?) = scope.launch {
        if (undo == null) snackbar.showSnackbar(message)
        else if (snackbar.showUndo(message)) undo()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Scanned cards") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(onClick = { nav.openSearch(CardTarget.Scans) }) { Icon(Icons.Default.Search, "Search") }
                ExtendedFloatingActionButton(
                    onClick = { nav.openScanner(CardTarget.Scans) },
                    icon = { Icon(Icons.Default.CameraAlt, null) },
                    text = { Text("Scan cards", fontSize = 18.sp) },
                    expanded = all.isNullOrEmpty(),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        bottomBar = {
            if (selectedIds.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                        ActionButton("To binder", Icons.Default.CollectionsBookmark, Modifier.weight(1f)) { action = ScanAction.BINDER }
                        ActionButton("To trade", Icons.Default.SwapHoriz, Modifier.weight(1f)) { action = ScanAction.TRADE }
                        ActionButton("Discard", Icons.Default.Delete, Modifier.weight(1f)) {
                            val ids = selectedIds
                            val n = selectedCount
                            scope.launch { report("Discarded $n card(s)", c.repo.discardScans(ids)) }
                        }
                    }
                }
            }
        },
    ) { pad ->
        if (all == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        if (all.isEmpty()) {
            Column(Modifier.padding(pad)) {
                EmptyState(
                    "📷",
                    "Nothing scanned yet",
                    "Scan a pile of cards here, then decide what to do with them: put them in a binder, add them to a trade, or throw them out of the list.",
                )
            }
            return@Scaffold
        }
        val total = all.sumOf { it.item.quantity }
        val value = all.sumOf { (it.unitPrice(priceType) ?: 0.0) * it.item.quantity }
        Column(Modifier.padding(pad)) {
            Text(
                "$total card${if (total == 1) "" else "s"} · worth ${Fmt.money(value)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$selectedCount selected", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = { unselected = emptySet() }) { Text("Select all") }
                TextButton(onClick = { unselected = all.map { it.item.id }.toSet() }) { Text("Select none") }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 160.dp),
            ) {
                items(all, key = { it.item.id }) { row ->
                    val id = row.item.id
                    val checked = id !in unselected
                    Box {
                        CardTile(
                            card = row.item.card,
                            price = row.unitPrice(priceType),
                            quantity = row.item.quantity,
                            language = row.item.language,
                            trend = row.trend,
                            footnote = if (row.item.hasJumbo && !row.item.card.oversized) "Big card? Tap it" else null,
                        ) { editing = row }
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on -> unselected = if (on) unselected - id else unselected + id },
                            modifier = Modifier.align(Alignment.TopStart).padding(2.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.85f)),
                        )
                    }
                }
            }
        }
    }

    editing?.let { row ->
        val item = row.item
        CardDialog(
            card = item.card,
            initial = EditValues(item.quantity, item.condition, item.language, null),
            priceType = priceType,
            confirmLabel = "Save",
            allowCustomPrice = false,
            onDismiss = { editing = null },
            onConfirm = { card, v ->
                editing = null
                scope.launch {
                    c.repo.updateScan(item.copy(card = card, quantity = v.quantity, condition = v.condition, language = v.language))
                }
            },
            onDelete = {
                editing = null
                scope.launch {
                    c.repo.deleteScan(item.id)
                    if (snackbar.showUndo("${item.card.name} removed")) c.repo.restoreScans(listOf(item))
                }
            },
            onChangeCard = {
                editing = null
                nav.openSearch(CardTarget.ReplaceScan(item.id), item.card.name)
            },
        )
    }

    when (action) {
        ScanAction.BINDER -> ToBinderDialog(selectedCount, onDismiss = { action = null }) { choice, keep ->
            action = null
            val ids = selectedIds
            val n = selectedCount
            scope.launch {
                val binderId = c.repo.resolve(choice)
                val undo = c.repo.scansToCollection(ids, binderId, keep)
                val name = choice.newName ?: binderName(binderId, c.db.binderDao().all())
                report("Added $n card(s) to $name", undo)
            }
        }
        ScanAction.TRADE -> ToTradeDialog(selectedCount, onDismiss = { action = null }) { tradeId, side, keep ->
            action = null
            val ids = selectedIds
            scope.launch {
                val id = c.repo.scansToTrade(ids, tradeId, side, keep)
                nav.navigate("trade/$id")
            }
        }
        null -> {}
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun ToBinderDialog(count: Int, onDismiss: () -> Unit, onConfirm: (BinderChoice, keep: Boolean) -> Unit) {
    var choice by remember { mutableStateOf(BinderChoice()) }
    var keep by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Put $count card(s) in “My cards”") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BinderPicker("Binder", choice, { choice = it }, suggestedName = "New cards ${Fmt.date(System.currentTimeMillis())}")
                CheckRow("Keep them in the scanned list", keep) { keep = it }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(choice, keep) }, enabled = choice.isValid) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ToTradeDialog(count: Int, onDismiss: () -> Unit, onConfirm: (tradeId: Long, side: String, keep: Boolean) -> Unit) {
    val c = LocalContext.current.container
    val open by remember { c.db.tradeDao().observeOpen() }.collectAsStateWithLifecycle(emptyList())
    var tradeId by remember { mutableStateOf(0L) }
    var side by remember { mutableStateOf(Side.GIVE) }
    var keep by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add $count card(s) to a trade") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text("Side", style = MaterialTheme.typography.labelLarge)
                RadioRow("I give", side == Side.GIVE) { side = Side.GIVE }
                RadioRow("I get", side == Side.GET) { side = Side.GET }
                Text("Trade", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                RadioRow("New trade", tradeId == 0L) { tradeId = 0L }
                open.forEach { t ->
                    RadioRow(t.partner.ifBlank { "Trade · ${Fmt.dateTime(t.createdAt)}" }, tradeId == t.id) { tradeId = t.id }
                }
                CheckRow("Keep them in the scanned list", keep) { keep = it }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(tradeId, side, keep) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
