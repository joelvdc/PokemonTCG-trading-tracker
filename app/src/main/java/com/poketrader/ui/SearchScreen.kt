package com.poketrader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.poketrader.AppContainer
import com.poketrader.container
import com.poketrader.data.CardRef
import com.poketrader.data.Binder
import com.poketrader.data.CardTarget
import com.poketrader.data.ImageKey
import com.poketrader.data.NumberFilter
import com.poketrader.data.SearchText
import com.poketrader.data.Side
import com.poketrader.data.TcgBrief
import com.poketrader.scan.CardTextParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** "Adding to: …" for the search and scanner screens, naming the binder for the collection targets. */
@Composable
fun rememberTargetLabel(t: CardTarget): String {
    val binders = rememberBinders()
    return when (t) {
        is CardTarget.TradeSide -> if (t.side == Side.GET) "Adding to: I get" else "Adding to: I give"
        is CardTarget.Collection ->
            if (t.binderId == Binder.UNSORTED) "Adding to: Collection" else "Adding to: ${binderName(t.binderId, binders)}"
        CardTarget.Scans -> "Adding to: Scanned cards"
        CardTarget.Wishlist -> "Adding to: Wishlist"
        else -> "Choose the right card"
    }
}

/** A search result with its set's name and printed size, ready to show. */
data class Hit(
    val brief: TcgBrief,
    val setName: String,
    val dataLang: String,
    val setOfficial: Int? = null,
    val setTotal: Int? = null,
) {
    /** "86/110" like on the card, or just "86" when the set size is unknown. */
    val numberLabel get() = setOfficial?.takeIf { it > 0 }?.let { "${brief.localId}/$it" } ?: brief.localId
}

/** Briefs → hits: no Pokémon TCG Pocket (digital) cards; cards with a picture first, then newest sets first. */
suspend fun toHits(c: AppContainer, briefs: List<TcgBrief>, dataLang: String): List<Hit> {
    c.sets.sets(dataLang)
    val paper = briefs.filter { !c.sets.isDigital(dataLang, it.setId) }
    val order = paper.associateWith { c.sets.order(dataLang, it.setId).let { o -> if (o == Int.MAX_VALUE) -1 else o } }
    return paper.sortedWith(compareBy<TcgBrief>({ it.thumbUrl(dataLang) == null }, { -(order[it] ?: 0) }))
        .map { b ->
            val set = c.sets.find(dataLang, b.setId)
            Hit(b, set?.name ?: b.setId, dataLang, set?.cardCount?.official, set?.cardCount?.total)
        }
}

@Composable
fun SearchScreen(nav: NavController, target: CardTarget, initialQuery: String?) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val priceType by c.settings.priceType.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf(initialQuery ?: "") }
    var selectedName by rememberSaveable { mutableStateOf(initialQuery) }
    // "pikachu 86/110": the name finds the cards, the number after it narrows them down.
    val (namePart, numberText) = SearchText.split(query)
    var japanese by rememberSaveable { mutableStateOf(initialQuery?.let { CardTextParser.containsJapanese(it) } ?: false) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var hits by remember { mutableStateOf<List<Hit>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf<CardRef?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { if (initialQuery == null) focus.requestFocus() }

    LaunchedEffect(namePart, selectedName) {
        if (selectedName != null) return@LaunchedEffect
        val q = namePart
        if (q.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        val lang = if (CardTextParser.containsJapanese(q)) "ja" else "en"
        try {
            suggestions = c.tcgdex.searchByName(q, lang).map { it.name }.distinct()
                .sortedWith(compareBy({ !it.startsWith(q, ignoreCase = true) }, { it.length }))
                .take(40)
            message = null
        } catch (e: Exception) {
            message = "Can't reach the card database — check the internet connection."
        }
    }

    LaunchedEffect(selectedName, japanese) {
        val name = selectedName
        if (name == null) {
            hits = null
            return@LaunchedEffect
        }
        loading = true
        hits = null
        message = null
        try {
            val typedJapanese = CardTextParser.containsJapanese(name)
            hits = when {
                typedJapanese -> toHits(c, c.tcgdex.cardsNamed(name, "ja"), "ja")
                !japanese -> toHits(c, c.tcgdex.cardsNamed(name, "en"), "en")
                else -> {
                    // Japanese cards have Japanese names: find them through the Pokédex number.
                    val first = c.tcgdex.cardsNamed(name, "en").firstOrNull()
                    val dex = first?.let { c.tcgdex.card(it.id, "en") }?.dexId?.firstOrNull()
                    if (dex == null) {
                        message = "Japanese search works for Pokémon (not Trainers or Energy). Try the scanner!"
                        emptyList()
                    } else toHits(c, c.tcgdex.cardsByDex(dex, "ja"), "ja")
                }
            }
        } catch (e: Exception) {
            message = "Can't reach the card database — check the internet connection."
        } finally {
            loading = false
        }
    }

    /** Chooses a suggested name, keeping any number already typed after it. */
    fun pick(name: String) {
        query = if (numberText.isEmpty()) name else "$name $numberText"
        selectedName = name
    }

    fun open(hit: Hit) = scope.launch {
        try {
            val card = c.tcgdex.card(hit.brief.id, hit.dataLang)
            opening = card?.defaultPrinting(hit.dataLang, preferHolo = false)
            if (card == null) snackbar.showSnackbar("Couldn't load that card")
        } catch (e: Exception) {
            snackbar.showSnackbar("Can't reach the card database")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (target.isReplace) "Other card" else "Search")
                        Text(rememberTargetLabel(target), style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { IconButton(onClick = { nav.safePopBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (!target.isReplace) {
                        IconButton(onClick = { if (nav.safePopBackStack()) nav.openScanner(target) }) { Icon(Icons.Default.CameraAlt, "Scan instead") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).imePadding()) {
            SearchField(
                query,
                { text ->
                    query = text
                    // Typing a number after the chosen name only narrows the results; changing the name starts over.
                    if (text.isEmpty() || !SearchText.keepsName(text, selectedName)) selectedName = null
                },
                "Name, + number if you like (Pikachu 86)",
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                onSearch = { if (selectedName == null) suggestions.firstOrNull()?.let { pick(it) } },
                fieldModifier = Modifier.focusRequester(focus),
            )
            if (selectedName != null && !CardTextParser.containsJapanese(selectedName ?: "")) {
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !japanese, onClick = { japanese = false }, label = { Text("🌍 International") })
                    FilterChip(selected = japanese, onClick = { japanese = true }, label = { Text("🇯🇵 Japanese") })
                }
            }
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            val all = hits
            val filter = NumberFilter.parse(numberText)
            val h = if (all == null || filter.isEmpty) all else all.filter { filter.matches(it.brief.localId, it.setOfficial, it.setTotal) }
            when {
                selectedName == null -> LazyColumn {
                    items(suggestions) { name ->
                        ListItem(
                            headlineContent = { Text(name, style = MaterialTheme.typography.titleMedium) },
                            modifier = Modifier.clickable { pick(name) },
                        )
                        HorizontalDivider()
                    }
                }
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                h != null && h.isEmpty() && message == null ->
                    if (all.isNullOrEmpty()) EmptyState("🔍", "No cards found", "Try another spelling.")
                    else EmptyState("🔍", "No card with number $numberText", "Check the number at the bottom of the card, or try the other tab.")
                h != null -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (filter.isEmpty) "${h.size} card(s) — tap the one you have"
                            else "${h.size} of ${all?.size ?: h.size} card(s) with number $numberText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                    items(h, key = { it.dataLang + it.brief.id }) { hit ->
                        Column(Modifier.clickable { open(hit) }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CardImage(hit.brief.thumbUrl(hit.dataLang), Modifier.fillMaxWidth(), fallbackKey = ImageKey(hit.brief.id, hit.dataLang), placeholder = hit.brief.name + "\n#" + hit.numberLabel)
                            Text(
                                hit.setName,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            Text("#${hit.numberLabel}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    opening?.let { card ->
        CardDialog(
            card = card,
            initial = EditValues(1, "NM", if (card.isJapanese) "JA" else "EN", null),
            priceType = priceType,
            confirmLabel = if (target.isReplace) "Choose" else "Add",
            allowCustomPrice = false,
            onDismiss = { opening = null },
            onConfirm = { chosen, v ->
                opening = null
                scope.launch {
                    if (target.isReplace) {
                        c.repo.replaceCard(target, chosen)
                        nav.popBackStack()
                    } else {
                        c.repo.add(target, chosen, v.language, v.quantity, v.condition)
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar("Added ${chosen.name}!")
                    }
                }
            },
        )
    }
}
