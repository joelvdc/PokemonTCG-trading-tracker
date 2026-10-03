package com.poketrader.ui

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import coil.network.HttpException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.poketrader.container
import com.poketrader.data.Balance
import com.poketrader.data.Binder
import com.poketrader.data.CONDITIONS
import com.poketrader.data.CardLinks
import com.poketrader.data.CardRef
import com.poketrader.data.ImageKey
import com.poketrader.data.TcgplayerImages
import com.poketrader.data.LANGUAGES
import com.poketrader.data.PriceEntity
import com.poketrader.data.PriceSet
import com.poketrader.data.PriceTrend
import com.poketrader.data.PriceType
import com.poketrader.data.Verdict
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object Fmt {
    private val eur = NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
        currency = Currency.getInstance("EUR")
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    fun money(v: Double?): String = if (v == null) "—" else eur.format(v)
    fun signedMoney(v: Double): String = (if (v > 0.004) "+" else if (v < -0.004) "−" else "") + eur.format(abs(v))
    fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
    fun dateTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    /** Parses "3,50" or "3.50"; null for blank/invalid. */
    fun parseMoney(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
}

/** A card picture at card proportions. With [enlargeUrl], tapping opens it full-screen. */
@Composable
fun CardImage(
    url: String?,
    modifier: Modifier = Modifier,
    enlargeUrl: String? = null,
    placeholder: String? = null,
    /** When [url] is null or doesn't exist, look for a TCGplayer picture of this card instead. */
    fallbackKey: ImageKey? = null,
) {
    val tcgplayer = LocalContext.current.container.tcgplayerImages
    var enlarged by remember { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    var missing by remember(url) { mutableStateOf(false) }
    var fallbackMissing by remember(url, fallbackKey) { mutableStateOf(false) }
    val needsFallback = fallbackKey != null && (url == null || missing)
    // -1 = still looking, 0 = TCGplayer has no picture either.
    val productId by produceState(-1, needsFallback, fallbackKey) {
        value = if (needsFallback && fallbackKey != null) tcgplayer.productId(fallbackKey) ?: 0 else 0
    }
    val usingFallback = url == null || missing
    val shown = if (!usingFallback) url else productId.takeIf { it > 0 && !fallbackMissing }?.let(TcgplayerImages::thumb)
    val shownLarge = if (!usingFallback) enlargeUrl else productId.takeIf { it > 0 && enlargeUrl != null }?.let(TcgplayerImages::large)
    val searching = usingFallback && needsFallback && productId == -1

    Box(
        modifier
            .aspectRatio(63f / 88f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (shownLarge != null) Modifier.clickable(onClickLabel = "Enlarge card") { enlarged = true } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        // No picture anywhere (many Japanese cards), or it couldn't be loaded (yet).
        if (shown == null || failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                Text("🃏", fontSize = 28.sp)
                if (placeholder != null) Text(placeholder, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                if (placeholder != null) {
                    Text(
                        if (shown == null && !searching) "no picture" else "loading…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (shown != null) {
            key(shown) {
                RetryingImage(
                    shown,
                    Modifier.fillMaxSize(),
                    onMissing = { if (usingFallback) fallbackMissing = true else missing = true },
                ) { failed = it }
            }
        }
    }
    if (enlarged && shownLarge != null) CardImageDialog(shown, shownLarge) { enlarged = false }
}

private data class ImageFailure(val missing: Boolean, val atReconnect: Int)

/** Pauses between retries of a failed picture; the last one repeats while the picture is on screen. */
private val RETRY_DELAYS_MS = longArrayOf(1_500, 3_000, 6_000, 12_000, 30_000, 60_000)

/**
 * Loads a picture and keeps trying if that fails: after a growing pause, and at once when the phone
 * reconnects (a failed load isn't retried by the image loader on its own, so a short network drop
 * used to leave cards blank). A picture that doesn't exist on the server (HTTP 404) isn't retried.
 */
@Composable
fun RetryingImage(
    url: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onMissing: () -> Unit = {},
    onFailedChange: (Boolean) -> Unit = {},
) {
    val reconnects by LocalContext.current.container.network.reconnects.collectAsStateWithLifecycle()
    var attempt by remember(url) { mutableIntStateOf(0) }
    var failure by remember(url) { mutableStateOf<ImageFailure?>(null) }
    LaunchedEffect(failure, reconnects) {
        val f = failure ?: return@LaunchedEffect
        if (f.missing) return@LaunchedEffect
        if (reconnects == f.atReconnect) delay(RETRY_DELAYS_MS[minOf(attempt, RETRY_DELAYS_MS.lastIndex)])
        failure = null
        attempt++
    }
    key(url, attempt) {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier,
            onSuccess = { onFailedChange(false) },
            onError = { state ->
                val missing = (state.result.throwable as? HttpException)?.response?.code == 404
                if (missing) onMissing()
                failure = ImageFailure(missing, reconnects)
                onFailedChange(true)
            },
        )
    }
}

/** Full-screen card image. Pinch or double-tap to zoom, drag to pan, tap to close. */
@Composable
fun CardImageDialog(smallUrl: String?, largeUrl: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var size by remember { mutableStateOf(IntSize.Zero) }
        fun clamp(o: Offset): Offset {
            val maxX = size.width * (scale - 1) / 2
            val maxY = size.height * (scale - 1) / 2
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.5f
                            offset = Offset.Zero
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = clamp(offset + pan)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .padding(16.dp)
                    .aspectRatio(63f / 88f)
                    .onSizeChanged { size = it }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                // The small picture is usually cached, so it shows at once while the sharp one loads.
                if (smallUrl != null) AsyncImage(model = smallUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                RetryingImage(largeUrl, Modifier.fillMaxSize(), contentDescription = "Card image")
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }
        }
    }
}

@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer, textColor: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Short badge for non-plain variants ("Reverse", "Holo", "1st Ed"); null for plain cards. */
fun variantBadge(card: CardRef): String? = when {
    card.oversized -> "Jumbo"
    card.firstEdition -> "1st Ed"
    card.variantLabel.startsWith("Reverse") -> "Reverse"
    card.variantLabel.startsWith("Holo") -> "Holo"
    card.variantLabel == "Normal" -> null
    else -> card.variantLabel.substringAfter("· ").take(10)
}

/** A card in a grid: picture with quantity/variant badges, price and name underneath. */
@Composable
fun CardTile(
    card: CardRef,
    price: Double?,
    quantity: Int,
    modifier: Modifier = Modifier,
    language: String = "EN",
    footnote: String? = null,
    footnoteIsWarning: Boolean = false,
    trend: PriceTrend? = null,
    onClick: () -> Unit,
) {
    Column(modifier.clickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            CardImage(
                card.thumbUrl,
                Modifier.fillMaxWidth(),
                placeholder = card.name + "\n#" + card.numberLabel,
                fallbackKey = ImageKey(card.cardId, card.dataLang, card.variantId),
            )
            if (quantity > 1) {
                Text(
                    "×$quantity",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            variantBadge(card)?.let { b ->
                Box(Modifier.align(Alignment.BottomStart).padding(4.dp)) { Tag(b, HoloColor, Color.White) }
            }
            if (language != "EN") {
                Box(Modifier.align(Alignment.TopStart).padding(4.dp)) { Tag(language, Color(0xFFBC002D), Color.White) }
            }
        }
        // The price of one card; a stack's total goes underneath.
        Text(
            Fmt.money(price),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 2.dp),
        )
        TrendBadge(trend)
        if (quantity > 1) {
            Text(
                "×$quantity · ${Fmt.money(price?.let { it * quantity })}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(card.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (footnote != null) {
            Text(
                footnote,
                style = MaterialTheme.typography.labelSmall,
                color = if (footnoteIsWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun <T> DropdownSelector(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(optionLabel(selected), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(optionLabel(o)) }, onClick = { onSelect(o); open = false })
            }
        }
    }
}

@Composable
fun QuantityStepper(value: Int, onChange: (Int) -> Unit, min: Int = 1) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { if (value > min) onChange(value - 1) }, enabled = value > min) {
            Icon(Icons.Default.Remove, "Less")
        }
        Text("$value", style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(48.dp), textAlign = TextAlign.Center)
        FilledTonalIconButton(onClick = { onChange(value + 1) }) { Icon(Icons.Default.Add, "More") }
    }
}

/** Big, friendly fairness verdict at the top of a trade. */
@Composable
fun VerdictBanner(balance: Balance, modifier: Modifier = Modifier) {
    val (emoji, title, color) = when (balance.verdict) {
        Verdict.EMPTY -> Triple("🃏", "Add cards to both sides", MaterialTheme.colorScheme.outline)
        Verdict.FAIR -> Triple("👍", "Fair trade!", VerdictColors.fair)
        Verdict.FAVORS_YOU -> Triple("🎉", "You get more", VerdictColors.favorsYou)
        Verdict.FAVORS_THEM -> Triple("⚠️", "You give more", VerdictColors.favorsThem)
    }
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.14f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 44.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
                Text(
                    "You give ${Fmt.money(balance.give)} · You get ${Fmt.money(balance.get)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (balance.verdict == Verdict.FAVORS_YOU || balance.verdict == Verdict.FAVORS_THEM) {
                    Text(
                        "Difference: ${Fmt.money(abs(balance.diff))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Shows "[message]" with an Undo button for 10 seconds. Returns true if Undo was tapped.
 * Replaces any message already showing, so repeated removals don't queue up.
 */
suspend fun SnackbarHostState.showUndo(message: String): Boolean {
    currentSnackbarData?.dismiss()
    return showSnackbar(message, actionLabel = "Undo", withDismissAction = true, duration = SnackbarDuration.Long) ==
        SnackbarResult.ActionPerformed
}

/** Price going up (green ▲), down (red ▼) or unchanged (▬), with the percentage; nothing without data. */
@Composable
fun TrendBadge(trend: PriceTrend?, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.labelMedium) {
    if (trend == null) return
    val color = when {
        trend.flat -> MaterialTheme.colorScheme.onSurfaceVariant
        trend.up -> TrendColors.up
        else -> TrendColors.down
    }
    Text(trend.label(), color = color, style = style, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = modifier)
}

@Composable
fun PriceTable(prices: PriceSet?, highlight: PriceType) {
    Column {
        Text("Cardmarket prices", style = MaterialTheme.typography.labelLarge)
        if (prices == null || prices.isEmpty) {
            Text("No Cardmarket price for this card yet.", style = MaterialTheme.typography.bodySmall)
            return
        }
        prices.trendChange?.let { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Trend vs 30-day average", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TrendBadge(t, style = MaterialTheme.typography.bodyMedium)
            }
        }
        PriceType.entries.forEach { t ->
            val hl = t == highlight
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text(t.label, Modifier.weight(1f), fontWeight = if (hl) FontWeight.Bold else null, style = MaterialTheme.typography.bodyMedium)
                Text(Fmt.money(prices.get(t)), fontWeight = if (hl) FontWeight.Bold else null, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

data class EditValues(
    val quantity: Int,
    val condition: String,
    val language: String,
    val customPrice: Double?,
    /** "My cards" only: the binder, and how many copies move there when it's changed. */
    val binderId: Long = Binder.UNSORTED,
    val move: Int = 0,
)

private data class VariantData(val printings: List<CardRef>, val prices: Map<Int, PriceEntity>)

/** Guide prices for [ref], or TCGdex's last-known trend when the guide has no entry. */
fun pricesOf(ref: CardRef, entity: PriceEntity?): PriceSet =
    entity?.toSet(ref.holoPrice)?.takeIf { !it.isEmpty } ?: PriceSet(trend = ref.fallbackPrice)

/**
 * Card details: big picture, variant choice (with prices), quantity, condition, language and an
 * optional agreed price. Used both to add a card and to edit one already in a trade/collection.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardDialog(
    card: CardRef,
    initial: EditValues,
    priceType: PriceType,
    confirmLabel: String,
    allowCustomPrice: Boolean,
    enabled: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (CardRef, EditValues) -> Unit,
    onDelete: (() -> Unit)? = null,
    onChangeCard: (() -> Unit)? = null,
    /** With binders, the card (or some of its copies) can be moved to another binder. */
    binders: List<Binder>? = null,
) {
    val c = LocalContext.current.container
    var selected by remember { mutableStateOf(card) }
    var v by remember { mutableStateOf(initial) }
    var customText by remember { mutableStateOf(initial.customPrice?.let { "%.2f".format(it) } ?: "") }
    var pickingPrinting by remember { mutableStateOf(false) }
    // The versions of the card shown: reloaded when another printing (set/number) is picked.
    val data by produceState<VariantData?>(null, selected.cardId, selected.dataLang) {
        value = null
        val shown = selected
        val printings = runCatching { c.tcgdex.card(shown.cardId, shown.dataLang)?.printings(shown.dataLang) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: listOf(shown)
        value = VariantData(printings, c.prices.pricesFor(printings.mapNotNull { it.cardmarketId }))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(card.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CardImage(selected.thumbUrl, Modifier.width(110.dp), enlargeUrl = selected.largeUrl, placeholder = "#" + selected.numberLabel, fallbackKey = ImageKey(selected.cardId, selected.dataLang, selected.variantId))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(selected.setName, style = MaterialTheme.typography.bodyMedium)
                        Text("#${selected.numberLabel} · ${selected.rarity}", style = MaterialTheme.typography.bodySmall)
                        if (selected.isJapanese) Text("Japanese print", style = MaterialTheme.typography.bodySmall)
                        if (selected.oversized) Text("Oversized (jumbo) card", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        val selectedPrices = pricesOf(selected, data?.prices?.get(selected.cardmarketId))
                        Text(
                            Fmt.money(selectedPrices.best(priceType)),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        TrendBadge(selectedPrices.trendChange, style = MaterialTheme.typography.titleSmall)
                        if (onChangeCard != null) TextButton(onClick = onChangeCard, enabled = enabled) { Text("Other card…") }
                    }
                }
                if (!enabled) {
                    Text(
                        "This trade is already in the collection. Tap Undo on the trade first to change it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val d = data
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if ((d?.printings?.size ?: 0) > 1) "Which version?" else "Printing",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { pickingPrinting = true }, enabled = enabled) { Text("Other printing…") }
                }
                if (d == null) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else if (d.printings.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        d.printings.forEach { p ->
                            FilterChip(
                                selected = p.variantId == selected.variantId,
                                onClick = { selected = p },
                                enabled = enabled,
                                label = {
                                    Text("${p.variantLabel}  ${Fmt.money(pricesOf(p, d.prices[p.cardmarketId]).best(priceType))}")
                                },
                            )
                        }
                    }
                }
                if (selected.firstEdition) {
                    Text(
                        "Cardmarket doesn't price 1st Edition separately, so this price may be too low. " +
                            "Agree on a price and type it below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("How many", Modifier.weight(1f))
                    if (enabled) QuantityStepper(v.quantity, { v = v.copy(quantity = it, move = minOf(v.move, it).coerceAtLeast(1)) }) else Text("${v.quantity}")
                }
                if (binders != null) {
                    DropdownSelector(
                        "Binder",
                        v.binderId,
                        listOf(Binder.UNSORTED) + binders.map { it.id },
                        { binderName(it, binders) },
                        { v = v.copy(binderId = it, move = v.quantity) },
                        Modifier.fillMaxWidth(),
                        enabled,
                    )
                    if (v.binderId != initial.binderId && v.quantity > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Cards to move", Modifier.weight(1f))
                            QuantityStepper(v.move.coerceIn(1, v.quantity), { v = v.copy(move = it.coerceAtMost(v.quantity)) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownSelector("Condition", v.condition, CONDITIONS.map { it.first }, { cd -> CONDITIONS.first { it.first == cd }.second }, { v = v.copy(condition = it) }, Modifier.weight(1f), enabled)
                    DropdownSelector("Language", v.language, LANGUAGES.map { it.first }, { l -> LANGUAGES.firstOrNull { it.first == l }?.second ?: l }, { v = v.copy(language = it) }, Modifier.weight(1f), enabled)
                }
                if (allowCustomPrice) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = {
                            customText = it
                            v = v.copy(customPrice = Fmt.parseMoney(it))
                        },
                        label = { Text("Agreed price per card (optional)") },
                        singleLine = true,
                        enabled = enabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                HorizontalDivider()
                PriceTable(pricesOf(selected, data?.prices?.get(selected.cardmarketId)), priceType)
                val uriHandler = LocalUriHandler.current
                OutlinedButton(
                    onClick = { uriHandler.openUri(CardLinks.cardmarket(selected)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (selected.cardmarketId != null) "See on Cardmarket" else "Search on Cardmarket")
                }
            }
            if (pickingPrinting) {
                PrintingPickerDialog(selected, onPick = { selected = it; pickingPrinting = false }, onDismiss = { pickingPrinting = false })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected, v) }, enabled = enabled && data != null) { Text(confirmLabel) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete, enabled = enabled) { Text("Remove", color = if (enabled) MaterialTheme.colorScheme.error else Color.Unspecified) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * Every printing of a card (same name: other sets, promos, numbers), as pictures to pick from.
 * Picking one hands back its default version (holo if the current one is holo and it has one).
 */
@Composable
fun PrintingPickerDialog(current: CardRef, onPick: (CardRef) -> Unit, onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    val hits by produceState<List<Hit>?>(null, current.name, current.dataLang) {
        value = runCatching { toHits(c, c.tcgdex.cardsNamed(current.name, current.dataLang), current.dataLang) }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which ${current.name}?") },
        text = {
            val list = hits
            when {
                list == null || loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text("No other printings found.")
                else -> LazyVerticalGrid(columns = GridCells.Adaptive(96.dp), modifier = Modifier.heightIn(max = 480.dp)) {
                    items(list, key = { it.brief.id }) { hit ->
                        val isCurrent = hit.brief.id == current.cardId
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable {
                                    loading = true
                                    scope.launch {
                                        val card = runCatching { c.tcgdex.card(hit.brief.id, hit.dataLang) }.getOrNull()
                                        loading = false
                                        val holo = current.variantLabel.contains("Holo", ignoreCase = true) && !current.variantLabel.startsWith("Reverse")
                                        card?.defaultPrinting(hit.dataLang, preferHolo = holo)?.let(onPick)
                                    }
                                }
                                .padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CardImage(
                                hit.brief.thumbUrl(hit.dataLang),
                                Modifier.fillMaxWidth(),
                                fallbackKey = ImageKey(hit.brief.id, hit.dataLang),
                                placeholder = hit.brief.name + "\n#" + hit.brief.localId,
                            )
                            Text(hit.setName, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                            Text("#${hit.brief.localId}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun EmptyState(emoji: String, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 56.sp)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
