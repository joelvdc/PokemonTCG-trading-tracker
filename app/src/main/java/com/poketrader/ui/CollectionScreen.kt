package com.poketrader.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.poketrader.container
import com.poketrader.data.Binder
import com.poketrader.data.BinderOrder
import com.poketrader.data.CardTarget
import com.poketrader.data.CollectionFilter
import com.poketrader.data.CollectionItem
import com.poketrader.data.CollectionRow
import com.poketrader.data.CollectionView
import com.poketrader.data.CsvImportProgress
import com.poketrader.data.ImageKey
import com.poketrader.data.PriceAlerts
import com.poketrader.data.Rarities
import com.poketrader.data.Repository
import com.poketrader.data.SwipeAction
import com.poketrader.data.UndoAction
import com.poketrader.data.WishlistItem
import com.poketrader.data.WishlistRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The wishlist's place in the binder bar (binder ids are positive). */
private const val WISHLIST = -2L

/** The wishlist shows with the same card views as the collection; condition is left empty so none is shown. */
private fun WishlistRow.asCollectionRow() =
    CollectionRow(CollectionItem(id = item.id, card = item.card, condition = "", quantity = item.quantity, addedAt = item.addedAt), price)

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
    val sort by c.settings.collectionSort.collectAsStateWithLifecycle()
    var filterText by rememberSaveable { mutableStateOf("") }
    val cardFilter = remember(filterText) { CollectionFilter.decode(filterText) }
    var sorting by remember { mutableStateOf(false) }
    var filtering by remember { mutableStateOf(false) }
    val wishRows by remember { c.db.wishlistDao().observeAll() }.collectAsStateWithLifecycle(emptyList())
    var editingWish by remember { mutableStateOf<WishlistRow?>(null) }
    // null is "All", Binder.UNSORTED is cards outside binders.
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CollectionRow?>(null) }
    var naming by remember { mutableStateOf<Binder?>(null) }
    var creating by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // Selecting cards (since 1.20): the picked stacks, or wishlist cards on the wishlist, by id.
    var selecting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<Long>()) }
    fun stopSelecting() { selecting = false; picked = emptySet() }
    fun togglePick(id: Long) { picked = if (id in picked) picked - id else picked + id }
    BackHandler(enabled = selecting) { stopSelecting() }
    var foldToBuy by rememberSaveable { mutableStateOf(false) }
    var foldOrdered by rememberSaveable { mutableStateOf(false) }
    var foldReceived by rememberSaveable { mutableStateOf(true) }
    val wishSectionsOn by c.settings.wishSections.collectAsStateWithLifecycle()
    val binderOrder by c.settings.binderOrder.collectAsStateWithLifecycle()
    var arranging by remember { mutableStateOf(false) }
    val toCollection by c.settings.receivedToCollection.collectAsStateWithLifecycle()
    val swipeRight by c.settings.swipeRight.collectAsStateWithLifecycle()
    val swipeLeft by c.settings.swipeLeft.collectAsStateWithLifecycle()
    var moving by remember { mutableStateOf<List<Long>?>(null) }
    var giving by remember { mutableStateOf<List<Long>?>(null) }
    var editingMany by remember { mutableStateOf<List<Long>?>(null) }
    var deletingMany by remember { mutableStateOf<List<CollectionItem>?>(null) }
    var receivingOwned by remember { mutableStateOf<List<Pair<WishlistItem, Int>>?>(null) }
    var exportIds by remember { mutableStateOf<Set<Long>?>(null) }
    // A received wishlist card on its way into the collection.
    var addingWish by remember { mutableStateOf<WishlistItem?>(null) }
    val joinAsk = rememberJoinCurrencyAsk()
    // The cards on screen, for "Select all shown".
    val shownIds = remember { mutableStateOf(setOf<Long>()) }
    // Price alerts (since 1.20): each wishlist card's latest price, and what it's compared with.
    val wishAlerts by remember { c.db.wishAlertDao().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val alertById = remember(wishAlerts) { wishAlerts.associateBy { it.wishId } }
    val priceSource by c.settings.priceSource.collectAsStateWithLifecycle()
    val alertType by c.settings.alertPriceType.collectAsStateWithLifecycle()
    val alertBasis = PriceAlerts.basis(priceSource, alertType)
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** Runs a change that can be undone, then offers Undo (nothing when it changed nothing). */
    fun undoable(message: (Int) -> String, change: suspend () -> Pair<Int, UndoAction>) {
        scope.launch {
            val (n, undo) = change()
            if (n > 0 && snackbar.showUndo(message(n))) undo()
        }
    }

    // The stats screen asked to show some cards (a tapped bar or slice).
    val jump by c.collectionJump.collectAsStateWithLifecycle()
    LaunchedEffect(jump) {
        val j = jump ?: return@LaunchedEffect
        c.collectionJump.value = null
        selected = j.binder
        filter = j.search
        filterText = if (j.filter.isEmpty) "" else j.filter.encode()
    }
    // A price alert's notification opens its card on the wishlist (since 1.20).
    val openWish by c.openWish.collectAsStateWithLifecycle()
    LaunchedEffect(openWish, wishRows) {
        val id = openWish ?: return@LaunchedEffect
        val row = wishRows.firstOrNull { it.item.id == id } ?: return@LaunchedEffect
        c.openWish.value = null
        selected = WISHLIST
        editingWish = row
    }

    // A deleted binder (or one merged away) drops back to "All".
    LaunchedEffect(binders, selected) {
        val s = selected
        if (s != null && s != Binder.UNSORTED && s != WISHLIST && binders.isNotEmpty() && binders.none { it.id == s }) selected = null
    }
    val currentBinder = binders.firstOrNull { it.id == selected }
    val wish = selected == WISHLIST
    LaunchedEffect(selected) { stopSelecting() }
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
    // The selected stacks as CSV (since 1.20).
    val selectedExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val ids = exportIds
        if (uri != null && ids != null) scope.launch {
            val csv = c.repo.exportCollectionCsv(priceType, null, ids)
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } }
            Toast.makeText(context, "${ids.size} stack(s) exported", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (selecting) {
                val pickedRows = if (wish) wishRows.filter { it.item.id in picked }.map { it.asCollectionRow() } else rows.orEmpty().filter { it.item.id in picked }
                SelectionTopBar(
                    pickedRows.sumOf { it.item.quantity }, pickedRows.sumOf { (it.unitPrice(priceType) ?: 0.0) * it.item.quantity },
                    onClose = { stopSelecting() },
                ) {
                    var more by remember { mutableStateOf(false) }
                    val ids = picked.toList()
                    if (wish) {
                        val pickedWish = wishRows.filter { it.item.id in picked }.map { it.item }
                        val anyWanted = pickedWish.any { it.receivedAt == null }
                        IconButton(onClick = { undoable({ "$it card(s) marked as ordered" }) { c.repo.setWishlistOrdered(ids, System.currentTimeMillis()).also { stopSelecting() } } }, enabled = anyWanted) {
                            Icon(WishActionIcons.ordered, "Mark as ordered")
                        }
                        IconButton(onClick = { undoable({ "$it card(s) marked as received" }) { c.repo.receiveWishlist(ids, toCollection).also { stopSelecting() } } }, enabled = anyWanted) {
                            Icon(WishActionIcons.received, "Mark as received")
                        }
                        IconButton(onClick = { undoable({ "$it card(s) deleted from the wishlist" }) { c.repo.deleteWishlistItems(ids).also { stopSelecting() } } }, enabled = ids.isNotEmpty()) {
                            Icon(WishActionIcons.delete, "Delete")
                        }
                        IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(
                                text = { Text("Back on the wishlist") }, leadingIcon = { Icon(WishActionIcons.back, null) },
                                enabled = pickedWish.any { it.receivedAt != null },
                                onClick = { more = false; undoable({ "$it card(s) back on the wishlist" }) { c.repo.unreceiveWishlist(ids).also { stopSelecting() } } },
                            )
                            DropdownMenuItem(
                                text = { Text("Not ordered") }, leadingIcon = { Icon(Icons.Default.Clear, null) },
                                enabled = pickedWish.any { it.orderedAt != null && it.receivedAt == null },
                                onClick = { more = false; undoable({ "$it card(s) no longer marked as ordered" }) { c.repo.setWishlistOrdered(ids, null).also { stopSelecting() } } },
                            )
                            DropdownMenuItem(
                                text = { Text("Share as a list") }, leadingIcon = { Icon(Icons.Default.Share, null) }, enabled = ids.isNotEmpty(),
                                onClick = {
                                    more = false
                                    shareText(context, "Pokémon wishlist", wishShareText(wishRows.filter { it.item.id in picked }.map { it.copy(item = it.item.copy(orderedAt = null, receivedAt = null)) }))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Select all shown") }, leadingIcon = { Icon(Icons.Default.Check, null) },
                                onClick = { more = false; picked = shownIds.value },
                            )
                        }
                    } else {
                        IconButton(onClick = { moving = ids }, enabled = ids.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Move to binder") }
                        IconButton(onClick = { giving = ids }, enabled = ids.isNotEmpty()) { Icon(Icons.Default.SwapHoriz, "Add to a trade") }
                        IconButton(onClick = { deletingMany = rows.orEmpty().filter { it.item.id in picked }.map { it.item } }, enabled = ids.isNotEmpty()) {
                            Icon(Icons.Default.Delete, "Delete")
                        }
                        IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(
                                text = { Text("Edit condition, language, price…") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, enabled = ids.isNotEmpty(),
                                onClick = { more = false; editingMany = ids },
                            )
                            DropdownMenuItem(
                                text = { Text("Share as a list") }, leadingIcon = { Icon(Icons.Default.Share, null) }, enabled = ids.isNotEmpty(),
                                onClick = { more = false; shareText(context, "Pokémon cards", c.repo.stacksText(rows.orEmpty().filter { it.item.id in picked }.map { it.item })) },
                            )
                            DropdownMenuItem(
                                text = { Text("Copy as a list") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, enabled = ids.isNotEmpty(),
                                onClick = {
                                    more = false
                                    val text = c.repo.stacksText(rows.orEmpty().filter { it.item.id in picked }.map { it.item })
                                    context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Pokémon cards", text))
                                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Export as CSV") }, leadingIcon = { Icon(Icons.Default.FileDownload, null) }, enabled = ids.isNotEmpty(),
                                onClick = { more = false; exportIds = picked; selectedExporter.launch("pokemon-selected.csv") },
                            )
                            DropdownMenuItem(
                                text = { Text("Select all shown") }, leadingIcon = { Icon(Icons.Default.Check, null) },
                                onClick = { more = false; picked = shownIds.value },
                            )
                        }
                    }
                }
            } else TopAppBar(
                title = { Text("Collection") },
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
                        if (wish) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Ordered cards in their own section") },
                                leadingIcon = { Checkbox(wishSectionsOn, onCheckedChange = null) },
                                onClick = { viewMenu = false; c.settings.setWishSections(!wishSectionsOn) },
                            )
                        }
                    }
                    IconButton(onClick = { sorting = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                    IconButton(onClick = { filtering = true }) {
                        BadgedBox(badge = { if (cardFilter.count > 0) Badge { Text("${cardFilter.count}") } }) { Icon(Icons.Default.FilterList, "Filter") }
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Select cards") },
                            leadingIcon = { Icon(Icons.Default.Checklist, null) },
                            onClick = { menu = false; selecting = true },
                        )
                        if (wish) {
                            val wanted = wishRows.filter { it.item.receivedAt == null }
                            DropdownMenuItem(
                                text = { Text("Mark all ordered cards as received") },
                                leadingIcon = { Icon(Icons.Default.TaskAlt, null) },
                                enabled = wanted.any { it.item.orderedAt != null },
                                onClick = {
                                    menu = false
                                    undoable({ "$it card(s) marked as received" }) { c.repo.receiveWishlist(c.repo.orderedWishlistIds(), toCollection) }
                                },
                            )
                            val anyOwned = wanted.any { (wishOwned[it.item.id]?.gotSince ?: 0) > 0 }
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("Mark the cards I now own as received")
                                        if (!anyOwned) Text("None of your wishlist cards is in your collection yet", style = MaterialTheme.typography.bodySmall)
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.Inventory, null) },
                                enabled = anyOwned,
                                onClick = { menu = false; scope.launch { receivingOwned = c.repo.ownedSinceAdding() } },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear received cards") },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                                enabled = wishRows.any { it.item.receivedAt != null },
                                onClick = { menu = false; undoable({ "$it received card(s) cleared" }) { c.repo.clearReceived() } },
                            )
                            DropdownMenuItem(
                                text = { Text("Share the wishlist") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                enabled = wanted.any { it.item.orderedAt == null },
                                onClick = { menu = false; shareText(context, "Pokémon wishlist", wishShareText(wishRows)) },
                            )
                            HorizontalDivider()
                        }
                        DropdownMenuItem(
                            text = { Text("New binder") },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                            onClick = { menu = false; creating = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Arrange binders…") },
                            leadingIcon = { Icon(Icons.Default.SwapVert, null) },
                            onClick = { menu = false; arranging = true },
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
                            text = { Text("Collection stats") },
                            leadingIcon = { Icon(Icons.Default.PieChart, null) },
                            onClick = { menu = false; nav.navigate("stats") },
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
            if (!selecting) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(onClick = { nav.openScanner(addTarget) }) { Icon(Icons.Default.CameraAlt, "Scan cards") }
                ExtendedFloatingActionButton(
                    onClick = { nav.openSearch(addTarget) },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Add card", fontSize = 18.sp) },
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
        val barKeys = remember(binders, binderOrder) { BinderOrder.arrange(binders, binderOrder) }
        val wishById = remember(wishRows) { wishRows.associateBy { it.item.id } }
        val inView = remember(all, wishRows, selected) {
            val source = if (wish) wishRows.map { it.asCollectionRow() } else all
            source.filter { r -> wish || selected == null || r.item.binderId == selected }
        }
        // Sorting and filtering by value follow the price source and its prices (since 1.16).
        val pricing = listOf(com.poketrader.data.Pricing.source, com.poketrader.data.Pricing.tcgplayer, com.poketrader.data.Pricing.usdPerEuro)
        val shown = remember(inView, filter, cardFilter, sort, priceType, pricing) {
            val f = filter.trim()
            inView.filter { r ->
                (f.isEmpty() || r.item.card.name.contains(f, true) || r.item.card.setName.contains(f, true)) && cardFilter.matches(r, priceType)
            }.sortedWith(sort.comparator(priceType))
        }
        SideEffect { shownIds.value = shown.map { it.item.id }.toSet() }
        val ownedSets = remember(inView) { ownedSets(inView) }
        fun footnote(row: CollectionRow): String? = when {
            wish -> wishById[row.item.id]?.let { wishLabel(it.item, wishOwned[it.item.id]) }
            selected == null && row.item.binderId != Binder.UNSORTED -> binderName(row.item.binderId, binders)
            else -> null
        }
        fun noteOf(row: CollectionRow): String? = if (wish) wishById[row.item.id]?.let { wishPriceLine(it.item, alertById[it.item.id], alertBasis) } else null
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
                // In the order arranged (since 1.20): Unsorted, the wishlist and the binders.
                items(barKeys, key = { it }) { k ->
                    val b = BinderOrder.binderOf(k, binders)
                    when {
                        k == BinderOrder.UNSORTED -> FilterChip(
                            selected = selected == Binder.UNSORTED,
                            onClick = { selected = Binder.UNSORTED },
                            label = { Text("${Binder.UNSORTED_NAME} · ${counts[Binder.UNSORTED] ?: 0}") },
                        )
                        k == BinderOrder.WISHLIST -> FilterChip(
                            selected = wish,
                            onClick = { selected = WISHLIST },
                            label = { Text("Wishlist · ${wishRows.filter { it.item.receivedAt == null }.sumOf { it.item.quantity }}") },
                            leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(18.dp)) },
                        )
                        b != null -> FilterChip(selected = selected == b.id, onClick = { selected = b.id }, label = { Text("${b.name} · ${counts[b.id] ?: 0}") })
                    }
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
            if (wish) {
                Text(
                    wishTotalLine(shown.filter { wishById[it.item.id]?.item?.receivedAt == null }, { id -> wishById[id]?.item?.orderedAt != null }, priceType),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().clickable { nav.navigate("value") }.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val n = "%,d".format(totalCards)
                    val cards = "$n card${if (totalCards == 1) "" else "s"}"
                    FittingText(
                        listOf(
                            "$cards · worth ${Fmt.money(totalValue)}",
                            "$cards · ${Fmt.money(totalValue)}",
                            "$cards · ${Fmt.wholeMoney(totalValue)}",
                            "$n · ${Fmt.shortMoney(totalValue)}",
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.AutoMirrored.Filled.ShowChart, "Value over time", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = { nav.navigate("stats") }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.PieChart, "Collection stats", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            SearchField(filter, { filter = it }, "Find a card", Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            if (!cardFilter.isEmpty) {
                ActiveFilterChips(cardFilter, ownedSets, onChange = { filterText = it.encode() }, onEdit = { filtering = true })
            }
            when {
                wish && wishRows.isEmpty() -> EmptyState(
                    "⭐",
                    "Your wishlist is empty",
                    "Pick “Wishlist” and scan or search the cards you want. They get a ★ when someone offers them in a trade.",
                )
                all.isEmpty() && !wish -> EmptyState(
                    "📦",
                    "No cards yet",
                    "Tap the camera button and hold your cards in front of it, or “Add card” to find them by name. Cards from finished trades show up here too.",
                )
                shown.isEmpty() && (filter.isNotBlank() || !cardFilter.isEmpty) -> EmptyState(
                    "🔍",
                    "No cards match",
                    "Try another search, or clear the filter.",
                )
                shown.isEmpty() -> EmptyState(
                    "📒",
                    if (selected == Binder.UNSORTED) "No unsorted cards" else "This binder is empty",
                    "Scan or search cards while this binder is open, send scanned cards here from the Scan tab, or move cards in from another binder.",
                )
                else -> {
                    // The wishlist's "Ordered" box on each card; null outside the wishlist, so no box shows.
                    fun orderedOf(row: CollectionRow): Boolean? = if (wish) wishById[row.item.id]?.item?.orderedAt != null else null
                    fun setOrdered(row: CollectionRow, on: Boolean) {
                        val w = wishById[row.item.id]?.item ?: return
                        scope.launch {
                            c.repo.setWishlistOrdered(w.id, if (on) System.currentTimeMillis() else null)
                            if (snackbar.showUndo(if (on) "${w.card.name} marked as ordered" else "${w.card.name} no longer marked as ordered")) {
                                c.repo.setWishlistOrdered(w.id, w.orderedAt)
                            }
                        }
                    }
                    // What a swipe does to a collection card (chosen in Settings, since 1.20).
                    fun swipe(row: CollectionRow, action: SwipeAction) {
                        val item = row.item
                        val name = item.card.name
                        when (action) {
                            SwipeAction.MOVE_TO_BINDER -> moving = listOf(item.id)
                            SwipeAction.ADD_TO_TRADE -> giving = listOf(item.id)
                            SwipeAction.ONE_MORE ->
                                undoable({ "$name: ${item.quantity} → ${item.quantity + 1}" }) { c.repo.changeQuantity(item.id, 1).let { (it.first?.let { 1 } ?: 0) to it.second } }
                            SwipeAction.ONE_LESS ->
                                undoable({ if (item.quantity > 1) "$name: ${item.quantity} → ${item.quantity - 1}" else "$name removed" }) {
                                    c.repo.changeQuantity(item.id, -1).let { (it.first?.let { 1 } ?: 0) to it.second }
                                }
                            SwipeAction.ADD_TO_WISHLIST -> scope.launch {
                                val r = c.repo.addToWishlist(item.card)
                                if (snackbar.showUndo("$name added to the wishlist")) c.repo.undoAdd(r)
                            }
                            SwipeAction.NOTHING -> {}
                        }
                    }
                    @Composable
                    fun swipesFor(row: CollectionRow): Pair<SwipeSpec?, SwipeSpec?> {
                        val w = wishById[row.item.id]?.item
                        if (wish && w != null) {
                            val name = w.card.name
                            val (r, l) = wishSwipes(
                                w,
                                onRight = {
                                    when {
                                        // Its copies are in the collection already (added when received): just off the wishlist.
                                        w.receivedAt != null && w.receivedFrom != null ->
                                            undoable({ "$name is in the collection already: taken off the wishlist" }) { c.repo.deleteWishlistItems(listOf(w.id)) }
                                        w.receivedAt != null -> addingWish = w
                                        w.orderedAt != null ->
                                            undoable({ "$name marked as received" + if (toCollection && !w.isPending) " and added to the collection" else "" }) { c.repo.receiveWishlist(listOf(w.id), toCollection) }
                                        else -> undoable({ "$name marked as ordered" }) { c.repo.setWishlistOrdered(listOf(w.id), System.currentTimeMillis()) }
                                    }
                                },
                                onLeft = { undoable({ "$name deleted from the wishlist" }) { c.repo.deleteWishlistItems(listOf(w.id)) } },
                            )
                            return r to l
                        }
                        fun spec(a: SwipeAction, look: Pair<androidx.compose.ui.graphics.vector.ImageVector, Color>) =
                            if (a == SwipeAction.NOTHING) null else SwipeSpec(a.label.removeSuffix("…"), look.first, look.second) { swipe(row, a) }
                        return spec(swipeRight, swipeLook(swipeRight)) to spec(swipeLeft, swipeLook(swipeLeft))
                    }
                    fun startPicking(row: CollectionRow) {
                        if (!selecting) { selecting = true; picked = setOf(row.item.id) } else togglePick(row.item.id)
                    }
                    @Composable
                    fun CardRow(row: CollectionRow) {
                        val received = wish && wishById[row.item.id]?.item?.receivedAt != null
                        val ordered = if (selecting || received) null else orderedOf(row)
                        val isPicked = selecting && row.item.id in picked
                        val click = { if (selecting) togglePick(row.item.id) else open(row) }
                        val (right, left) = swipesFor(row)
                        val price = row.unitPrice(priceType)
                        when (view) {
                            CollectionView.LIST -> SwipeRow(right, left, enabled = !selecting) {
                                CollectionListRow(row, price, footnote(row), noteOf(row), ordered, { setOrdered(row, it) }, isPicked, { startPicking(row) }, click)
                            }
                            CollectionView.COMPACT -> SwipeRow(right, left, enabled = !selecting) {
                                CompactRow(row, price, footnote(row), noteOf(row), ordered, { setOrdered(row, it) }, isPicked, { startPicking(row) }, click)
                            }
                            // Picture tiles are too small to swipe: a long press selects.
                            CollectionView.CARDS -> CardTile(
                                card = row.item.card,
                                price = price,
                                quantity = row.item.quantity,
                                language = row.item.language,
                                trend = row.trend,
                                footnote = footnote(row),
                                picked = isPicked,
                                onLongClick = { startPicking(row) },
                                note = noteOf(row),
                                onClick = click,
                            )
                        }
                    }
                    // The wishlist in sections (since 1.20); the collection is one section without a header.
                    val sections = if (wish) wishSections(shown, { wishById[it]?.item }, wishSectionsOn) else listOf(WishSection("all", null, shown))
                    fun folded(key: String) = when (key) {
                        "tobuy" -> foldToBuy
                        "ordered" -> foldOrdered
                        "received" -> foldReceived
                        else -> false
                    }
                    fun toggle(key: String) = when (key) {
                        "tobuy" -> foldToBuy = !foldToBuy
                        "ordered" -> foldOrdered = !foldOrdered
                        "received" -> foldReceived = !foldReceived
                        else -> {}
                    }
                    when (view) {
                        CollectionView.CARDS -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(104.dp),
                            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 160.dp),
                        ) {
                            for (sec in sections) {
                                sec.title?.let { t ->
                                    item(key = "section-${sec.key}", span = { GridItemSpan(maxLineSpan) }) {
                                        SectionHeader(t, sec.rows.sumOf { it.item.quantity }, folded(sec.key)) { toggle(sec.key) }
                                    }
                                }
                                if (!folded(sec.key)) gridItems(sec.rows, key = { it.item.id }) { row -> CardRow(row) }
                            }
                        }
                        else -> LazyColumn(
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 160.dp),
                            verticalArrangement = Arrangement.spacedBy(if (view == CollectionView.LIST) 6.dp else 0.dp),
                        ) {
                            for (sec in sections) {
                                sec.title?.let { t ->
                                    item(key = "section-${sec.key}") { SectionHeader(t, sec.rows.sumOf { it.item.quantity }, folded(sec.key)) { toggle(sec.key) } }
                                }
                                if (!folded(sec.key)) items(sec.rows, key = { it.item.id }) { row -> CardRow(row) }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { row -> CollectionCardDialog(row, nav, snackbar, scope) { editing = null } }

    if (sorting) {
        SortDialog(sort, onDismiss = { sorting = false }) { c.settings.setCollectionSort(it); sorting = false }
    }
    if (filtering) {
        val source = (if (wish) wishRows.map { it.asCollectionRow() } else rows.orEmpty())
            .filter { r -> wish || selected == null || r.item.binderId == selected }
        FilterDialog(
            cardFilter, ownedSets(source),
            rarities = source.map { it.item.card.rarity }.filter { it.isNotBlank() }.distinct().sortedByDescending { Rarities.rank(it) },
            variants = source.map { it.item.card.variantLabel }.distinct().sorted(),
            languages = source.map { it.item.language }.filter { it.isNotBlank() }.distinct().sorted(),
            count = { f -> source.filter { f.matches(it, priceType) }.sumOf { it.item.quantity } },
            onDismiss = { filtering = false },
            onApply = { filterText = it.encode(); filtering = false },
        )
    }

    editingWish?.let { row ->
        WishlistDialog(
            row, wishOwned[row.item.id], priceType, WishAlertInfo(alertById[row.item.id], alertBasis),
            onDismiss = { editingWish = null },
            onSave = { updated ->
                editingWish = null
                // A new level asks for notifications (Android 13 and later), and looks the card's prices up.
                if (updated.alertBelow != null && row.item.alertBelow == null && android.os.Build.VERSION.SDK_INT >= 33 && !c.wishWatch.notifier.allowed()) {
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                scope.launch {
                    // Ticked "Received" in the window: marked like a swipe (also into the collection if chosen).
                    val receiving = updated.receivedAt != null && row.item.receivedAt == null
                    c.repo.updateWishlistItem(updated.copy(notes = updated.notes?.trim()?.ifEmpty { null }, receivedAt = if (receiving) null else updated.receivedAt))
                    if (receiving) {
                        val (_, undo) = c.repo.receiveWishlist(listOf(updated.id), toCollection)
                        if (snackbar.showUndo("${updated.card.name} marked as received")) undo()
                    } else if (updated != row.item) c.appScope.launch { runCatching { c.wishWatch.check(notify = false, only = setOf(updated.id)) } }
                }
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

    // Swiping and selecting (since 1.20).
    moving?.let { ids ->
        val one = if (ids.size == 1) rows.orEmpty().firstOrNull { it.item.id == ids[0] }?.item else null
        val cards = one?.quantity ?: rows.orEmpty().filter { it.item.id in ids }.sumOf { it.item.quantity }
        MoveToBinderDialog(
            if (one != null) "Move ${one.card.name}" else "Move $cards card(s)", one?.quantity ?: 1,
            onDismiss = { moving = null },
        ) { choice, copies ->
            moving = null
            stopSelecting()
            scope.launch {
                val binderId = c.repo.resolve(choice)
                val partly = one != null && copies < one.quantity
                // Joining a stack priced in another currency asks which one to keep.
                val joins = if (partly) c.repo.editedStacks(one!!, binderId, copies) else c.repo.movedStacks(ids, binderId)
                joinAsk.ask(c.repo.joinCurrencies(joins)) { into ->
                    scope.launch {
                        val (n, undo) = if (partly) c.repo.moveCopies(one!!.id, binderId, copies, into) else c.repo.moveStacks(ids, binderId, into)
                        if (n > 0 && snackbar.showUndo("Moved $n card(s) to ${binderName(binderId, c.db.binderDao().all())}")) undo()
                    }
                }
            }
        }
    }
    giving?.let { ids ->
        val one = if (ids.size == 1) rows.orEmpty().firstOrNull { it.item.id == ids[0] }?.item else null
        GiveInTradeDialog(
            if (one != null) "Give ${one.card.name} in a trade" else "Give ${rows.orEmpty().filter { it.item.id in ids }.sumOf { it.item.quantity }} card(s) in a trade",
            one?.quantity ?: 1,
            onDismiss = { giving = null },
        ) { tradeId, copies ->
            giving = null
            stopSelecting()
            scope.launch {
                val (_, n, undo) = c.repo.stacksToTrade(ids, tradeId, if (one != null) copies else null)
                if (snackbar.showUndo("Added $n card(s) to the trade")) undo()
            }
        }
    }
    editingMany?.let { ids ->
        EditStacksDialog(ids.size, onDismiss = { editingMany = null }) { condition, language, price, currency ->
            editingMany = null
            stopSelecting()
            val edit = Repository.StackEdit(condition, language, price, currency)
            scope.launch {
                joinAsk.ask(c.repo.joinCurrencies(c.repo.editedStacks(ids, edit))) { into ->
                    undoable({ "$it stack(s) changed" }) { c.repo.editStacks(ids, edit, into) }
                }
            }
        }
    }
    deletingMany?.let { items ->
        DeleteStacksDialog(items, onDismiss = { deletingMany = null }) {
            deletingMany = null
            stopSelecting()
            undoable({ "$it card(s) deleted" }) { c.repo.deleteStacks(items.map { it.id }) }
        }
    }
    if (arranging) {
        ArrangeBindersDialog(BinderOrder.arrange(binders, binderOrder), { k -> barLabel(k, binders) }, onDismiss = { arranging = false }) {
            arranging = false
            c.settings.setBinderOrder(it)
        }
    }
    addingWish?.let { w ->
        AddWishToCollectionDialog(
            w, wishOwned[w.id]?.gotSince ?: 0, rows.orEmpty().map { it.item },
            onDismiss = { addingWish = null },
            onTakeOff = {
                addingWish = null
                undoable({ "${w.card.name} taken off the wishlist" }) { c.repo.deleteWishlistItems(listOf(w.id)) }
            },
        ) { v ->
            addingWish = null
            scope.launch {
                val binderId = c.repo.resolve(v.binder)
                val card = v.card!!
                joinAsk.ask(c.repo.joinCurrencies(c.repo.wishJoin(card, v.condition, v.language, binderId, v.price, v.currency))) { into ->
                    scope.launch {
                        val undo = c.repo.wishToCollection(w.id, card, v.condition, v.language, v.quantity, binderId, v.price, v.currency, into)
                        if (snackbar.showUndo("${v.quantity}× ${w.card.name} added to ${binderName(binderId, c.db.binderDao().all())}")) undo()
                    }
                }
            }
        }
    }
    receivingOwned?.let { got ->
        ReceiveOwnedDialog(got, onDismiss = { receivingOwned = null }) {
            receivingOwned = null
            undoable({ "$it card(s) marked as received" }) { c.repo.receiveOwned() }
        }
    }
    JoinCurrencyDialog(joinAsk)

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
                joinAsk.ask(c.repo.mergeCurrencies(from, into)) { currency ->
                    scope.launch {
                        c.repo.mergeBinder(from, into, deleteSource, currency)
                        selected = into
                        snackbar.showSnackbar("Moved the cards of $fromName into ${binderName(into, binders)}")
                    }
                }
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

/** A place in the binder bar, named for "Arrange binders". */
private fun barLabel(key: String, binders: List<Binder>): String = when (key) {
    BinderOrder.UNSORTED -> Binder.UNSORTED_NAME
    BinderOrder.WISHLIST -> "★ Wishlist"
    else -> BinderOrder.binderOf(key, binders)?.name ?: key
}

/**
 * A card in the list view: small picture, name, set and number, version/language tags, price. On the
 * wishlist, [ordered] shows the "Ordered" box and [note] the alert line (since 1.20).
 */
@Composable
private fun CollectionListRow(
    row: CollectionRow,
    price: Double?,
    binder: String?,
    note: String?,
    ordered: Boolean?,
    onOrdered: (Boolean) -> Unit,
    picked: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val item = row.item
    val card = item.card
    Card(
        colors = CardDefaults.cardColors(containerColor = if (picked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().combinedClickable(onLongClick = onLongClick, onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (picked) PickedMark(Modifier.padding(end = 6.dp))
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
                    if (item.language.isNotBlank() && item.language != "EN") Tag(item.language, Color(0xFFBC002D), Color.White)
                    if (item.condition.isNotBlank() && item.condition != "NM") Tag(item.condition)
                    binder?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(approx(row) + Fmt.money(price), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (item.quantity > 1) {
                    Text(Fmt.money(price?.let { it * item.quantity }) + " total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TrendBadge(row.trend, style = MaterialTheme.typography.labelSmall)
            }
            ordered?.let { OrderedBox(it, onOrdered) }
        }
    }
}

/** A card as one line of text: quantity, name, set and number, tags and price. */
@Composable
private fun CompactRow(
    row: CollectionRow,
    price: Double?,
    binder: String?,
    note: String?,
    ordered: Boolean?,
    onOrdered: (Boolean) -> Unit,
    picked: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val item = row.item
    val card = item.card
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).pickedBackground(picked).combinedClickable(onLongClick = onLongClick, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (picked) PickedMark(Modifier.padding(end = 6.dp))
            Text("${item.quantity}×", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(30.dp))
            Text(card.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                listOfNotNull(card.setName, "#${card.numberLabel}".takeIf { card.localId.isNotBlank() }, variantBadge(card), item.language.takeIf { it.isNotBlank() && it != "EN" }, binder, note).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                approx(row) + Fmt.money(price),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 8.dp).widthIn(min = 64.dp),
            )
            ordered?.let { OrderedBox(it, onOrdered) }
        }
        HorizontalDivider(thickness = 0.5.dp)
    }
}

/** The wishlist's "Ordered" box at the end of a card. Since 1.20. */
@Composable
private fun OrderedBox(ordered: Boolean, onChange: (Boolean) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 4.dp)) {
        Checkbox(checked = ordered, onCheckedChange = onChange, modifier = Modifier.size(36.dp))
        Text("Ordered", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** The sets among [rows], for the filter, by name. */
private fun ownedSets(rows: List<CollectionRow>): List<OwnedSet> =
    rows.groupBy { it.item.card.setId }.map { (id, r) -> OwnedSet(id, r.first().item.card.setName, r.sumOf { it.item.quantity }) }.sortedBy { it.name.lowercase() }
