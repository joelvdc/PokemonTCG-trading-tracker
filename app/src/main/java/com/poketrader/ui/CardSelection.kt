package com.poketrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poketrader.container
import com.poketrader.data.BinderChoice
import com.poketrader.data.BinderOrder
import com.poketrader.data.CONDITIONS
import com.poketrader.data.CollectionItem
import com.poketrader.data.CollectionRow
import com.poketrader.data.LANGUAGES
import com.poketrader.data.SwipeAction
import com.poketrader.data.WishlistItem

// Swiping and selecting cards in the collection and on the wishlist, as in MTG Trader. Since 1.20.

/** What a swipe in one direction does: its colour, icon and words behind the card, and the action. */
data class SwipeSpec(val label: String, val icon: ImageVector, val color: Color, val action: () -> Unit)

/**
 * The wishlist's swipes: right moves a card one step on, To buy → Ordered → Received → the collection
 * (or, when its copies are in the collection already, off the wishlist); left deletes it.
 */
@Composable
fun wishSwipes(w: WishlistItem, onRight: () -> Unit, onLeft: () -> Unit): Pair<SwipeSpec, SwipeSpec> = when {
    w.receivedAt != null && w.receivedFrom != null -> SwipeSpec("Off the wishlist (in the collection)", Icons.Default.TaskAlt, SwipeColors.neutral, onRight)
    w.receivedAt != null -> SwipeSpec("Add to the collection", Icons.Default.Inventory, MaterialTheme.colorScheme.primary, onRight)
    w.orderedAt != null -> SwipeSpec("Received", Icons.Default.TaskAlt, SwipeColors.good, onRight)
    else -> SwipeSpec("Ordered", Icons.Default.LocalShipping, MaterialTheme.colorScheme.tertiary, onRight)
} to SwipeSpec("Delete", Icons.Default.Delete, MaterialTheme.colorScheme.error, onLeft)

/** A collection swipe's look (the action is the screen's). */
@Composable
fun swipeLook(a: SwipeAction): Pair<ImageVector, Color> = when (a) {
    SwipeAction.MOVE_TO_BINDER -> Icons.AutoMirrored.Filled.DriveFileMove to MaterialTheme.colorScheme.primary
    SwipeAction.ADD_TO_TRADE -> Icons.Default.SwapHoriz to MaterialTheme.colorScheme.tertiary
    SwipeAction.ONE_MORE -> Icons.Default.Add to SwipeColors.good
    SwipeAction.ONE_LESS -> Icons.Default.Remove to MaterialTheme.colorScheme.error
    SwipeAction.ADD_TO_WISHLIST -> Icons.Default.Star to SwipeColors.wish
    SwipeAction.NOTHING -> Icons.Default.Close to Color.Gray
}

object SwipeColors {
    val good = Color(0xFF2E7D32)
    val neutral = Color(0xFF546E7A)
    val wish = Color(0xFFB8860B)
}

/**
 * A card row that can be swiped: [right] when swiped to the right (start to end), [left] the other way.
 * A swipe counts once it's gone 40% of the way, so a slip of the thumb doesn't; the colour and words
 * behind the card show what it will do. The row comes back to its place after the action (cards that
 * leave the list, e.g. deleted ones, simply go).
 */
@Composable
fun SwipeRow(right: SwipeSpec?, left: SwipeSpec?, enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled || (right == null && left == null)) {
        content()
        return
    }
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.4f })
    val r by rememberUpdatedState(right)
    val l by rememberUpdatedState(left)
    LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> r?.action?.invoke()
            SwipeToDismissBoxValue.EndToStart -> l?.action?.invoke()
            SwipeToDismissBoxValue.Settled -> return@LaunchedEffect
        }
        state.snapTo(SwipeToDismissBoxValue.Settled)
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = right != null,
        enableDismissFromEndToStart = left != null,
        backgroundContent = {
            val toRight = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val spec = if (toRight) right else if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) left else null
            if (spec != null) SwipeBackground(spec, toRight)
        },
    ) { content() }
}

/** What's behind a card being swiped: its action's colour, icon and name on the side it's swiped from. */
@Composable
fun SwipeBackground(spec: SwipeSpec, toRight: Boolean, modifier: Modifier = Modifier.fillMaxSize()) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(spec.color).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (toRight) Arrangement.Start else Arrangement.End,
    ) {
        if (!toRight) Text(spec.label, color = Color.White, style = MaterialTheme.typography.titleSmall)
        if (!toRight) Spacer(Modifier.width(8.dp))
        Icon(spec.icon, null, tint = Color.White)
        if (toRight) Spacer(Modifier.width(8.dp))
        if (toRight) Text(spec.label, color = Color.White, style = MaterialTheme.typography.titleSmall)
    }
}

/** The tick on a picked card. */
@Composable
fun PickedMark(modifier: Modifier = Modifier) {
    Icon(
        Icons.Default.CheckCircle, "Selected", modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
        tint = MaterialTheme.colorScheme.primary,
    )
}

/** A picked card's background. */
@Composable
fun Modifier.pickedBackground(picked: Boolean): Modifier =
    if (picked) background(MaterialTheme.colorScheme.secondaryContainer) else this

/** A section of the wishlist ("Ordered · 3"), folded and unfolded by tapping it. */
@Composable
fun SectionHeader(title: String, count: Int, folded: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$title · $count", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Icon(if (folded) Icons.Default.ExpandMore else Icons.Default.ExpandLess, if (folded) "Show" else "Hide", tint = MaterialTheme.colorScheme.primary)
    }
}

/** One of the wishlist's sections, as [wishSections] lays them out. */
data class WishSection(val key: String, val title: String?, val rows: List<CollectionRow>)

/**
 * The wishlist's sections: To buy, Ordered and Received ([sections] on), or one list with the ordered
 * cards last and then Received ([sections] off). Received cards show newest first; empty sections are
 * left out. [rows] are already sorted; [wish] tells each row's wishlist card.
 */
fun wishSections(rows: List<CollectionRow>, wish: (Long) -> WishlistItem?, sections: Boolean): List<WishSection> {
    val (received, wanted) = rows.partition { wish(it.item.id)?.receivedAt != null }
    val (ordered, toBuy) = wanted.partition { wish(it.item.id)?.orderedAt != null }
    val receivedNewest = received.sortedByDescending { wish(it.item.id)?.receivedAt ?: 0L }
    return listOfNotNull(
        if (sections) WishSection("tobuy", "To buy", toBuy).takeIf { toBuy.isNotEmpty() } else WishSection("wanted", null, toBuy + ordered).takeIf { wanted.isNotEmpty() },
        if (sections) WishSection("ordered", "Ordered", ordered).takeIf { ordered.isNotEmpty() } else null,
        WishSection("received", "Received", receivedNewest).takeIf { received.isNotEmpty() },
    )
}

/** Picks a binder (or a new one) for moving cards; with [maxCopies] > 1 (one stack), how many copies too. */
@Composable
fun MoveToBinderDialog(title: String, maxCopies: Int, onDismiss: () -> Unit, onMove: (BinderChoice, copies: Int) -> Unit) {
    var choice by remember { mutableStateOf(BinderChoice()) }
    var copies by remember { mutableStateOf(maxCopies) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BinderPicker("Move to", choice, { choice = it })
                if (maxCopies > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Cards to move", Modifier.weight(1f))
                        QuantityStepper(copies, { copies = it.coerceIn(1, maxCopies) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onMove(choice, copies) }, enabled = choice.isValid) { Text("Move") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks a trade (an open one, or a new one) for cards you give; with [maxCopies] > 1 (one stack), how many. */
@Composable
fun GiveInTradeDialog(title: String, maxCopies: Int, onDismiss: () -> Unit, onAdd: (tradeId: Long?, copies: Int) -> Unit) {
    val c = LocalContext.current.container
    val open by remember { c.db.tradeDao().observeOpen() }.collectAsStateWithLifecycle(emptyList())
    var tradeId by remember { mutableStateOf<Long?>(null) }
    var copies by remember { mutableStateOf(1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text("They go on the “I give” side.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RadioRow("New trade", tradeId == null) { tradeId = null }
                open.forEach { t -> RadioRow(t.partner.ifBlank { "Trade · ${Fmt.dateTime(t.createdAt)}" }, tradeId == t.id) { tradeId = t.id } }
                if (maxCopies > 1) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Cards", Modifier.weight(1f))
                        QuantityStepper(copies, { copies = it.coerceIn(1, maxCopies) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(tradeId, copies) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Sets condition, language and/or purchase price on the selected stacks; what's left as "Keep" stays. */
@Composable
fun EditStacksDialog(count: Int, onDismiss: () -> Unit, onSave: (condition: String?, language: String?, price: Double?, currency: String?) -> Unit) {
    var condition by remember { mutableStateOf<String?>(null) }
    var language by remember { mutableStateOf<String?>(null) }
    val priceInput = remember { MoneyInput.of(null, null) }
    val price = priceInput.amount?.takeIf { it >= 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit $count stack(s)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Only what you change here is changed; the rest stays as each card has it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DropdownSelector("Condition", condition, listOf<String?>(null) + CONDITIONS.map { it.first }, { code -> code?.let { k -> CONDITIONS.first { it.first == k }.let { "${it.second} (${it.first})" } } ?: "Keep as they are" }, { condition = it }, Modifier.fillMaxWidth())
                DropdownSelector("Language", language, listOf<String?>(null) + LANGUAGES.map { it.first }, { code -> code?.let { k -> LANGUAGES.firstOrNull { it.first == k }?.second ?: k } ?: "Keep as they are" }, { language = it }, Modifier.fillMaxWidth())
                MoneyField(priceInput, "What you paid per card", Modifier.fillMaxWidth(), placeholder = "Keep as they are")
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(condition, language, price, priceInput.code) }, enabled = condition != null || language != null || price != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks before deleting the selected stacks. */
@Composable
fun DeleteStacksDialog(stacks: List<CollectionItem>, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val copies = stacks.sumOf { it.quantity }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $copies card(s)?") },
        text = {
            Text("${stacks.size} stack(s) leave your collection. A restore point (backup) is saved first, and “Undo” brings them back right after.")
        },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Mark the cards I now own as received": which ones, before anything happens. */
@Composable
fun ReceiveOwnedDialog(got: List<Pair<WishlistItem, Int>>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark the cards you own as received?") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "These wishlist cards are in your collection now (more copies than when you added them). They move to Received; " +
                        "nothing is added to the collection, they're already there.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                got.forEach { (w, n) ->
                    Text(
                        "$n× ${w.card.name} (${w.card.setName} #${w.card.numberLabel})" + if (n < w.quantity) " (of ${w.quantity} wanted; the rest stays)" else "",
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Mark as received") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Icons of the wishlist's selection actions. */
object WishActionIcons {
    val ordered = Icons.Default.LocalShipping
    val received = Icons.Default.TaskAlt
    val back = Icons.AutoMirrored.Filled.Undo
    val delete = Icons.Default.Delete
}

/** The top bar while selecting: close, how many cards are selected and their value, and the [actions]. */
@Composable
fun SelectionTopBar(cards: Int, value: Double, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Stop selecting") } },
        title = {
            Column {
                Text("$cards selected", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Fmt.money(value), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

/** Moves Unsorted, the wishlist and every binder up or down in the binder bar. */
@Composable
fun ArrangeBindersDialog(keys: List<String>, label: (String) -> String, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var order by remember { mutableStateOf(keys) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Arrange binders") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "The order of the bar above the collection, and of the binder lists. “All” always comes first.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                order.forEachIndexed { i, k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label(k), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { order = BinderOrder.move(order, i, -1) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Up") }
                        IconButton(onClick = { order = BinderOrder.move(order, i, 1) }, enabled = i < order.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Down") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(order) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Opens Android's share sheet with [text]. */
fun shareText(context: android.content.Context, subject: String, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_SUBJECT, subject).putExtra(android.content.Intent.EXTRA_TEXT, text)
    context.startActivity(android.content.Intent.createChooser(send, subject))
}
