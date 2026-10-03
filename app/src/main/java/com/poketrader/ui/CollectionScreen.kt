package com.poketrader.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.poketrader.container
import com.poketrader.data.Binder
import com.poketrader.data.CardTarget
import com.poketrader.data.CollectionRow
import com.poketrader.data.CollectionView
import com.poketrader.data.ImageKey
import com.poketrader.data.CsvImportProgress
import com.poketrader.data.CollectionItem
import com.poketrader.data.WishlistRow
import android.content.Intent
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TaskAlt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Each order with its natural direction first ([forward]) and the reverse ([backward]). */
private enum class SortBy(val label: String, val forward: String, val backward: String) {
    NAME("Name", "A to Z", "Z to A"),
    VALUE("Value (per card)", "Most valuable first", "Cheapest first"),
    RECENT("When added", "Newest first", "Oldest first"),
    SET("Set", "A to Z", "Z to A"),
}

/** The wishlist's place in the binder bar (binder ids are positive). */
private const val WISHLIST = -2L

/** The wishlist shows with the same card views as "My cards". */
private fun WishlistRow.asCollectionRow() =
    CollectionRow(CollectionItem(id = item.id, card = item.card, quantity = item.quantity, addedAt = item.addedAt), price)

@Composable
fun CollectionScreen(nav: NavController) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val rows by remember { c.db.collectionDao().observeAll() }.collectAsStateWithLifecycle(null)
    val binders = rememberBinders()
    val priceType by c.settings.priceType.collectAsStateWithLifecycle()
    val view by c.settings.collectionView.collectAsStateWithLifecycle()
    var viewMenu by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SortBy.NAME) }
    var reversed by rememberSaveable { mutableStateOf(false) }
    val wishRows by remember { c.db.wishlistDao().observeAll() }.collectAsStateWithLifecycle(emptyList())
    var editingWish by remember { mutableStateOf<WishlistRow?>(null) }
    // null is "All", Binder.UNSORTED is cards outside binders.
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CollectionRow?>(null) }
    var naming by remember { mutableStateOf<Binder?>(null) }
    var creating by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    // A deleted binder (or one merged away) drops back to "All".
    LaunchedEffect(binders, selected) {
        val s = selected
        if (s != null && s != Binder.UNSORTED && s != WISHLIST && binders.isNotEmpty() && binders.none { it.id == s }) selected = null
    }
    val currentBinder = binders.firstOrNull { it.id == selected }
    val wish = selected == WISHLIST
    val addTarget = if (wish) CardTarget.Wishlist else CardTarget.Collection(selected ?: Binder.UNSORTED)
    val wishOwned = remember(wishRows, rows) { c.repo.wishlistOwned(wishRows.map { it.item }, rows.orEmpty().map { it.item }) }

    val csvImport by c.repo.csvImport.collectAsStateWithLifecycle()
    val csvImportResult by c.repo.csvImportResult.collectAsStateWithLifecycle()
    LaunchedEffect(csvImportResult) {
        val msg = csvImportResult ?: return@LaunchedEffect
        c.repo.consumeCsvImportResult()
        snackbar.showSnackbar(msg)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val appContext = context.applicationContext
            val started = c.repo.startCsvImport(
                readText = {
                    withContext(Dispatchers.IO) {
                        appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    } ?: ""
                },
                defaultBinder = selected ?: Binder.UNSORTED,
            )
            if (!started) scope.launch { snackbar.showSnackbar("An import is already running") }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val csv = c.repo.exportCollectionCsv(priceType, selected)
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
                    IconButton(onClick = { viewMenu = true }) {
                        Icon(
                            when (view) {
                                CollectionView.CARDS -> Icons.Default.GridView
                                CollectionView.LIST -> Icons.AutoMirrored.Filled.ViewList
                                CollectionView.COMPACT -> Icons.Default.ViewHeadline
                            },
                            "View",
                        )
                    }
                    DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                        CollectionView.entries.forEach { v ->
                            DropdownMenuItem(
                                text = { Text(v.label, fontWeight = if (v == view) FontWeight.Bold else null) },
                                leadingIcon = { if (v == view) Icon(Icons.Default.Check, null) },
                                onClick = { c.settings.setCollectionView(v); viewMenu = false },
                            )
                        }
                    }
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortBy.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label, fontWeight = if (s == sort) FontWeight.Bold else null) },
                                leadingIcon = { if (s == sort) Icon(Icons.Default.Check, null) },
                                onClick = { if (s != sort) reversed = false; sort = s; sortMenu = false },
                            )
                        }
                        HorizontalDivider()
                        listOf(false to sort.forward, true to sort.backward).forEach { (r, label) ->
                            DropdownMenuItem(
                                text = { Text(label, fontWeight = if (r == reversed) FontWeight.Bold else null) },
                                leadingIcon = { if (r == reversed) Icon(Icons.Default.Check, null) },
                                onClick = { reversed = r; sortMenu = false },
                            )
                        }
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (wish) {
                            DropdownMenuItem(
                                text = { Text("Remove the cards I got since adding them") },
                                leadingIcon = { Icon(Icons.Default.TaskAlt, null) },
                                enabled = wishOwned.values.any { it.gotSince > 0 },
                                onClick = {
                                    menu = false
                                    scope.launch {
                                        val (n, undo) = c.repo.removeOwnedFromWishlist()
                                        if (snackbar.showUndo("Took $n card(s) off the wishlist")) undo()
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share the wishlist") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                enabled = wishRows.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    val text = wishRows.joinToString("\n") { "${it.item.quantity}× ${it.item.card.name} (${it.item.card.setName} #${it.item.card.numberLabel})" }
                                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(Intent.EXTRA_SUBJECT, "Pokémon wishlist").putExtra(Intent.EXTRA_TEXT, text)
                                    context.startActivity(Intent.createChooser(send, "Share the wishlist"))
                                },
                            )
                            HorizontalDivider()
                        }
                        DropdownMenuItem(
                            text = { Text("New binder") },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                            onClick = { menu = false; creating = true },
                        )
                        if (currentBinder != null) {
                            DropdownMenuItem(
                                text = { Text("Rename “${currentBinder.name}”") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = { menu = false; naming = currentBinder },
                            )
                            DropdownMenuItem(
                                text = { Text("Merge “${currentBinder.name}” into…") },
                                leadingIcon = { Icon(Icons.Default.CallMerge, null) },
                                onClick = { menu = false; merging = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete “${currentBinder.name}”") },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                onClick = { menu = false; deleting = true },
                            )
                        }
                        if (selected == Binder.UNSORTED) {
                            DropdownMenuItem(
                                text = { Text("Move all Unsorted cards to…") },
                                leadingIcon = { Icon(Icons.Default.CallMerge, null) },
                                onClick = { menu = false; merging = true },
                            )
                        }
                        if (!wish) HorizontalDivider()
                        if (!wish) DropdownMenuItem(
                            text = { Text("Value over time") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ShowChart, null) },
                            onClick = { menu = false; nav.navigate("value") },
                        )
                        if (!wish) DropdownMenuItem(
                            text = { Text(if (selected == null) "Export (CSV backup)" else "Export this binder (CSV)") },
                            leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                            onClick = {
                                menu = false
                                val name = if (selected == null) "pokemon-cards" else "pokemon-" + binderName(selected ?: 0, binders).replace(Regex("[^A-Za-z0-9]+"), "-").lowercase()
                                exporter.launch("$name.csv")
                            },
                        )
                        if (!wish) DropdownMenuItem(
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
                SmallFloatingActionButton(onClick = { nav.openSearch(addTarget) }) { Icon(Icons.Default.Search, "Search") }
                ExtendedFloatingActionButton(
                    onClick = { nav.openScanner(addTarget) },
                    icon = { Icon(Icons.Default.CameraAlt, null) },
                    text = { Text("Scan cards", fontSize = 18.sp) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { pad ->
        val all = rows
        if (all == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val counts = remember(all) { all.groupBy { it.item.binderId }.mapValues { (_, r) -> r.sumOf { it.item.quantity } } }
        val wishById = remember(wishRows) { wishRows.associateBy { it.item.id } }
        val shown = remember(all, wishRows, filter, sort, reversed, priceType, selected) {
            val f = filter.trim()
            val source = if (wish) wishRows.map { it.asCollectionRow() } else all
            source.filter { r ->
                (wish || selected == null || r.item.binderId == selected) &&
                    (f.isEmpty() || r.item.card.name.contains(f, true) || r.item.card.setName.contains(f, true))
            }.let { list ->
                when (sort) {
                    SortBy.NAME -> list
                    // By the price of one card, so a stack of cheap cards doesn't outrank a single valuable one.
                    SortBy.VALUE -> list.sortedByDescending { it.unitPrice(priceType) ?: 0.0 }
                    SortBy.RECENT -> list.sortedByDescending { it.item.addedAt }
                    SortBy.SET -> list.sortedWith(compareBy({ it.item.card.setName }, { it.item.card.localId.filter(Char::isDigit).toIntOrNull() ?: 0 }))
                }.let { if (reversed) it.asReversed() else it }
            }
        }
        fun footnote(row: CollectionRow): String? = when {
            wish -> wishById[row.item.id]?.let { w ->
                val o = wishOwned[w.item.id]
                listOfNotNull(
                    if (w.item.anyVariant) null else "only this version",
                    when {
                        o == null -> null
                        o.gotSince > 0 -> "✓ got ${o.gotSince}"
                        o.owned > 0 -> "you have ${o.owned}"
                        else -> null
                    },
                ).joinToString(" · ").ifEmpty { null }
            }
            selected == null && row.item.binderId != Binder.UNSORTED -> binderName(row.item.binderId, binders)
            else -> null
        }
        fun open(row: CollectionRow) {
            if (wish) editingWish = wishById[row.item.id] else editing = row
        }
        val totalCards = shown.sumOf { it.item.quantity }
        val totalValue = shown.sumOf { (it.unitPrice(priceType) ?: 0.0) * it.item.quantity }

        Column(Modifier.padding(pad)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "all") {
                    FilterChip(selected = selected == null, onClick = { selected = null }, label = { Text("All · ${counts.values.sum()}") })
                }
                item(key = "unsorted") {
                    FilterChip(
                        selected = selected == Binder.UNSORTED,
                        onClick = { selected = Binder.UNSORTED },
                        label = { Text("${Binder.UNSORTED_NAME} · ${counts[Binder.UNSORTED] ?: 0}") },
                    )
                }
                item(key = "wishlist") {
                    FilterChip(
                        selected = wish,
                        onClick = { selected = WISHLIST },
                        label = { Text("Wishlist · ${wishRows.sumOf { it.item.quantity }}") },
                        leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(18.dp)) },
                    )
                }
                items(binders, key = { it.id }) { b ->
                    FilterChip(selected = selected == b.id, onClick = { selected = b.id }, label = { Text("${b.name} · ${counts[b.id] ?: 0}") })
                }
                item(key = "new") {
                    AssistChip(
                        onClick = { creating = true },
                        label = { Text("New binder") },
                        leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
                    )
                }
            }
            csvImport?.let { p -> CsvImportCard(p) }
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !wish) { nav.navigate("value") }.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (wish) "$totalCards card${if (totalCards == 1) "" else "s"} wanted · ${Fmt.money(totalValue)} to buy them"
                    else "$totalCards card${if (totalCards == 1) "" else "s"} · worth ${Fmt.money(totalValue)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (!wish) Icon(Icons.AutoMirrored.Filled.ShowChart, "Value over time", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            }
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Find a card") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) { Icon(Icons.Default.Clear, "Clear") } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            when {
                wish && wishRows.isEmpty() -> EmptyState(
                    "⭐",
                    "Your wishlist is empty",
                    "Pick “Wishlist” and scan or search the cards you want. They get a ★ when someone offers them in a trade.",
                )
                all.isEmpty() && !wish -> EmptyState(
                    "📦",
                    "No cards yet",
                    "Tap “Scan cards” and hold your cards in front of the camera. Cards from finished trades show up here too.",
                )
                shown.isEmpty() && filter.isBlank() -> EmptyState(
                    "📒",
                    if (selected == Binder.UNSORTED) "No unsorted cards" else "This binder is empty",
                    "Scan or search cards while this binder is open, send scanned cards here from the Scan tab, or move cards in from another binder.",
                )
                view == CollectionView.CARDS -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 160.dp),
                ) {
                    items(shown, key = { it.item.id }) { row ->
                        CardTile(
                            card = row.item.card,
                            price = row.unitPrice(priceType),
                            quantity = row.item.quantity,
                            language = row.item.language,
                            trend = row.trend,
                            footnote = footnote(row),
                        ) { open(row) }
                    }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(if (view == CollectionView.LIST) 6.dp else 0.dp),
                ) {
                    items(shown, key = { it.item.id }) { row ->
                        val binder = footnote(row)
                        if (view == CollectionView.LIST) CollectionListRow(row, row.unitPrice(priceType), binder) { open(row) }
                        else CompactRow(row, row.unitPrice(priceType), binder) { open(row) }
                    }
                }
            }
        }
    }

    editing?.let { row ->
        val item = row.item
        CardDialog(
            card = item.card,
            initial = EditValues(item.quantity, item.condition, item.language, null, item.binderId, item.quantity, item.notes, item.purchasePrice),
            priceType = priceType,
            confirmLabel = "Save",
            allowCustomPrice = false,
            binders = binders,
            showNotes = true,
            onDismiss = { editing = null },
            onConfirm = { card, v ->
                editing = null
                scope.launch {
                    c.repo.saveCollectionEdit(
                        item.copy(
                            card = card, quantity = v.quantity, condition = v.condition, language = v.language,
                            notes = v.notes?.trim()?.ifEmpty { null }, purchasePrice = v.purchasePrice,
                        ),
                        v.binderId, v.move,
                    )
                }
            },
            onDelete = {
                editing = null
                scope.launch {
                    c.repo.deleteCollectionItem(item.id)
                    if (snackbar.showUndo("${item.card.name} removed")) c.repo.restoreCollectionItem(item)
                }
            },
            onChangeCard = {
                editing = null
                nav.openSearch(CardTarget.ReplaceCollectionItem(item.id), item.card.name)
            },
        )
    }

    editingWish?.let { row ->
        WishlistDialog(
            row, wishOwned[row.item.id], priceType,
            onDismiss = { editingWish = null },
            onSave = { updated ->
                editingWish = null
                scope.launch { c.repo.updateWishlistItem(updated.copy(notes = updated.notes?.trim()?.ifEmpty { null })) }
            },
            onDelete = {
                editingWish = null
                scope.launch {
                    c.repo.deleteWishlistItem(row.item.id)
                    if (snackbar.showUndo("${row.item.card.name} taken off the wishlist")) c.repo.restoreWishlistItem(row.item)
                }
            },
        )
    }

    if (creating) {
        BinderNameDialog(null, onDismiss = { creating = false }) { name ->
            creating = false
            scope.launch { selected = c.repo.createBinder(name) }
        }
    }
    naming?.let { b ->
        BinderNameDialog(b, onDismiss = { naming = null }) { name ->
            naming = null
            scope.launch { c.repo.renameBinder(b.id, name) }
        }
    }
    if (merging) {
        val from = selected ?: Binder.UNSORTED
        val fromName = binderName(from, binders)
        MergeBinderDialog(from, rows?.filter { it.item.binderId == from }?.sumOf { it.item.quantity } ?: 0, onDismiss = { merging = false }) { into, deleteSource ->
            merging = false
            scope.launch {
                c.repo.mergeBinder(from, into, deleteSource)
                selected = into
                snackbar.showSnackbar("Moved the cards of $fromName into ${binderName(into, binders)}")
            }
        }
    }
    if (deleting && currentBinder != null) {
        val b = currentBinder
        DeleteBinderDialog(b, rows?.filter { it.item.binderId == b.id }?.sumOf { it.item.quantity } ?: 0, onDismiss = { deleting = false }) { deleteCards ->
            deleting = false
            scope.launch {
                c.repo.deleteBinder(b.id, deleteCards)
                selected = null
                snackbar.showSnackbar("Binder “${b.name}” deleted")
            }
        }
    }
}

/** A card in the list view: small picture, name, set and number, variant/language tags, price. */
@Composable
private fun CollectionListRow(row: CollectionRow, price: Double?, binder: String?, onClick: () -> Unit) {
    val item = row.item
    val card = item.card
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            CardImage(
                card.thumbUrl,
                Modifier.width(48.dp),
                placeholder = card.name,
                fallbackKey = ImageKey(card.cardId, card.dataLang, card.variantId),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    (if (item.quantity > 1) "${item.quantity}× " else "") + card.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${card.setName} · #${card.numberLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    variantBadge(card)?.let { Tag(it, HoloColor, Color.White) }
                    if (item.language != "EN") Tag(item.language, Color(0xFFBC002D), Color.White)
                    if (item.condition != "NM") Tag(item.condition)
                    binder?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.money(price), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (item.quantity > 1) {
                    Text(Fmt.money(price?.let { it * item.quantity }) + " total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TrendBadge(row.trend, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** A card as one line of text: quantity, name, set and number, tags and price. */
@Composable
private fun CompactRow(row: CollectionRow, price: Double?, binder: String?, onClick: () -> Unit) {
    val item = row.item
    val card = item.card
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${item.quantity}×", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(30.dp))
            Text(card.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                listOfNotNull(card.setName, "#${card.numberLabel}", variantBadge(card), item.language.takeIf { it != "EN" }, binder).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                Fmt.money(price),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 8.dp).widthIn(min = 64.dp),
            )
        }
        HorizontalDivider(thickness = 0.5.dp)
    }
}

/** Progress of a running CSV import. */
@Composable
private fun CsvImportCard(p: CsvImportProgress) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                if (p.total == 0) "Importing CSV…"
                else "Importing CSV: looking up cards, ${"%,d".format(p.done)} of ${"%,d".format(p.total)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (p.total == 0) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            else LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Text(
                "You can keep using the app meanwhile.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
