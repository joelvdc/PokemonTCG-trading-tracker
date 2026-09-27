package com.poketrader.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.poketrader.container
import com.poketrader.data.CardTarget
import com.poketrader.data.CollectionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SortBy(val label: String) { NAME("Name"), VALUE("Most valuable"), RECENT("Newest first"), SET("Set") }

@Composable
fun CollectionScreen(nav: NavController) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val rows by remember { c.db.collectionDao().observeAll() }.collectAsStateWithLifecycle(null)
    val priceType by c.settings.priceType.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SortBy.NAME) }
    var sortMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CollectionRow?>(null) }
    var busy by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } ?: ""
                val r = c.repo.importCollectionCsv(text)
                snackbar.showSnackbar("Imported ${r.imported} card(s)" + if (r.notFound > 0) " · ${r.notFound} not found" else "")
            } catch (e: Exception) {
                snackbar.showSnackbar("Import failed: ${e.message}")
            } finally {
                busy = false
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val csv = c.repo.exportCollectionCsv(priceType)
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } }
            Toast.makeText(context, "Cards exported", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("My cards") },
                actions = {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortBy.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label, fontWeight = if (s == sort) FontWeight.Bold else null) },
                                onClick = { sort = s; sortMenu = false },
                            )
                        }
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Export (CSV backup)") },
                            leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                            onClick = { menu = false; exporter.launch("pokemon-cards.csv") },
                        )
                        DropdownMenuItem(
                            text = { Text("Import CSV backup") },
                            leadingIcon = { Icon(Icons.Default.FileUpload, null) },
                            onClick = { menu = false; importer.launch(arrayOf("*/*")) },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(onClick = { nav.openSearch(CardTarget.Collection) }) { Icon(Icons.Default.Search, "Search") }
                ExtendedFloatingActionButton(
                    onClick = { nav.openScanner(CardTarget.Collection) },
                    icon = { Icon(Icons.Default.CameraAlt, null) },
                    text = { Text("Scan cards", fontSize = 18.sp) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { pad ->
        val all = rows
        if (all == null || busy) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val shown = remember(all, filter, sort, priceType) {
            val f = filter.trim()
            all.filter { r -> f.isEmpty() || r.item.card.name.contains(f, true) || r.item.card.setName.contains(f, true) }
                .let { list ->
                    when (sort) {
                        SortBy.NAME -> list
                        SortBy.VALUE -> list.sortedByDescending { (it.unitPrice(priceType) ?: 0.0) * it.item.quantity }
                        SortBy.RECENT -> list.sortedByDescending { it.item.addedAt }
                        SortBy.SET -> list.sortedWith(compareBy({ it.item.card.setName }, { it.item.card.localId.filter(Char::isDigit).toIntOrNull() ?: 0 }))
                    }
                }
        }
        val totalCards = shown.sumOf { it.item.quantity }
        val totalValue = shown.sumOf { (it.unitPrice(priceType) ?: 0.0) * it.item.quantity }

        Column(Modifier.padding(pad)) {
            Text(
                "$totalCards card${if (totalCards == 1) "" else "s"} · worth ${Fmt.money(totalValue)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Find a card") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) { Icon(Icons.Default.Clear, "Clear") } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (all.isEmpty()) {
                EmptyState(
                    "📦",
                    "No cards yet",
                    "Tap “Scan cards” and hold your cards in front of the camera. Cards from finished trades show up here too.",
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 160.dp),
                ) {
                    items(shown, key = { it.item.id }) { row ->
                        CardTile(
                            card = row.item.card,
                            price = row.unitPrice(priceType),
                            quantity = row.item.quantity,
                            language = row.item.language,
                        ) { editing = row }
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
                    c.repo.updateCollectionItem(item.copy(card = card, quantity = v.quantity, condition = v.condition, language = v.language))
                }
            },
            onDelete = { editing = null; scope.launch { c.repo.deleteCollectionItem(item.id) } },
            onChangeCard = {
                editing = null
                nav.openSearch(CardTarget.ReplaceCollectionItem(item.id), item.card.name)
            },
        )
    }
}
