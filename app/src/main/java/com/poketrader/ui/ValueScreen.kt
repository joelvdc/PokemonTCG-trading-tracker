package com.poketrader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.poketrader.container
import com.poketrader.data.Binder
import com.poketrader.data.CollectionRow
import androidx.compose.foundation.clickable
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import com.poketrader.data.PriceMove
import com.poketrader.data.ValueHistory
import com.poketrader.data.ValuePoint
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

private enum class ValueRange(val label: String, val days: Long?) { MONTH("1 month", 31), QUARTER("3 months", 92), YEAR("1 year", 366), ALL("All", null) }

/** The collection's value over time (saved once a day), and the cards whose price moved most lately. Since 1.16. */
@Composable
fun ValueScreen(nav: NavController) {
    val c = LocalContext.current.container
    val priceType by c.settings.priceType.collectAsStateWithLifecycle()
    val binders = rememberBinders()
    // null is the whole collection; since 1.14 one binder can be shown.
    var binder by rememberSaveable { mutableStateOf<Long?>(null) }
    val history by remember(priceType, binder) { c.history.observe(priceType, binder) }.collectAsStateWithLifecycle(emptyList())
    val allRows by remember { c.db.collectionDao().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val rows = remember(allRows, binder) { if (binder == null) allRows else allRows.filter { it.item.binderId == binder } }
    var range by rememberSaveable { mutableStateOf(ValueRange.QUARTER) }
    val now = remember(rows, priceType) { ValueHistory.totalValue(rows, priceType) }
    val points = remember(history, range) {
        val from = range.days?.let { LocalDate.now().minusDays(it) }
        history.filter { from == null || !it.day.isBefore(from) }
    }
    val risers = remember(rows) { ValueHistory.biggestMoves(rows, up = true) }
    val fallers = remember(rows) { ValueHistory.biggestMoves(rows, up = false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<CollectionRow?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Collection value") },
                navigationIcon = { IconButton(onClick = { nav.safePopBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (binders.isNotEmpty()) {
                item(key = "binders") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { FilterChip(selected = binder == null, onClick = { binder = null }, label = { Text("All cards") }) }
                        item { FilterChip(selected = binder == Binder.UNSORTED, onClick = { binder = Binder.UNSORTED }, label = { Text(Binder.UNSORTED_NAME) }) }
                        items(binders, key = { it.id }) { b -> FilterChip(selected = binder == b.id, onClick = { binder = b.id }, label = { Text(b.name) }) }
                    }
                }
            }
            item(key = "now") {
                Column {
                    Text(Fmt.money(now), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${rows.sumOf { it.item.quantity }} cards · ${priceType.label} (change it in Settings)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (points.size >= 2) {
                        val diff = points.last().value - points.first().value
                        val pct = if (points.first().value > 0) diff / points.first().value * 100 else 0.0
                        Text(
                            (if (diff >= 0) "+" else "−") + Fmt.money(abs(diff)) + " (%+.1f%%) since %s".format(pct, shortDate(points.first().day)),
                            color = if (diff >= 0) TrendColors.up else TrendColors.down,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }
            item(key = "ranges") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ValueRange.entries.forEach { r -> FilterChip(selected = r == range, onClick = { range = r }, label = { Text(r.label) }) }
                }
            }
            item(key = "chart") {
                if (points.size < 2) {
                    Text(
                        (if (binder == null) "The app saves your collection's value once a day (when it opens and after each price update), "
                        else "The app saves each binder's value once a day since version 1.14, ") +
                            "so the chart fills in as the days go by." + if (history.size == 1) " First value saved ${shortDate(history.first().day)}." else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ValueChart(points)
                }
            }
            if (risers.isNotEmpty()) {
                item(key = "up") { MovesHeader("Rising lately") }
                items(risers, key = { "u${it.row.item.id}" }) { MoveRow(it) { opened = it.row } }
            }
            if (fallers.isNotEmpty()) {
                item(key = "down") { MovesHeader("Falling lately") }
                items(fallers, key = { "d${it.row.item.id}" }) { MoveRow(it) { opened = it.row } }
            }
            if (risers.isNotEmpty() || fallers.isNotEmpty()) {
                item(key = "note") {
                    Text(
                        "Cardmarket's trend price against its 30-day average, for all the copies you own.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    opened?.let { row -> CollectionCardDialog(row, nav, snackbar, scope) { opened = null } }
}

private fun shortDate(d: LocalDate): String = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

@Composable
private fun MovesHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun MoveRow(m: PriceMove, onClick: () -> Unit) {
    val item = m.row.item
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CardImage(
            item.card.thumbUrl, Modifier.width(40.dp), placeholder = item.card.name,
            fallbackKey = com.poketrader.data.ImageKey(item.card.cardId, item.card.dataLang, item.card.variantId),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text((if (item.quantity > 1) "${item.quantity}× " else "") + item.card.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull("${item.card.setName} · #${item.card.numberLabel}", variantBadge(item.card)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (m.change >= 0) "+" else "−") + Fmt.money(abs(m.change)),
                color = if (m.change >= 0) TrendColors.up else TrendColors.down,
                fontWeight = FontWeight.SemiBold,
            )
            Text("%+.0f%%".format(m.pct), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A line chart of the values; touch or drag along it to read a day. */
@Composable
private fun ValueChart(points: List<ValuePoint>) {
    var picked by remember(points) { mutableStateOf<Int?>(null) }
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val min = points.minOf { it.value }
    val max = points.maxOf { it.value }
    val span = (max - min).takeIf { it > 0.01 } ?: 1.0
    val first = points.first().day.toEpochDay()
    val days = (points.last().day.toEpochDay() - first).coerceAtLeast(1)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp)) {
            val shown = picked?.let { points[it] } ?: points.last()
            Text("${shortDate(shown.day)}: ${Fmt.money(shown.value)} · ${shown.cards} cards", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row {
                Canvas(
                    Modifier.weight(1f).height(180.dp)
                        .pointerInput(points) {
                            fun pick(x: Float) {
                                val day = first + (x / size.width * days).toLong()
                                picked = points.indices.minByOrNull { abs(points[it].day.toEpochDay() - day) }
                            }
                            detectTapGestures { pick(it.x) }
                        }
                        .pointerInput(points) {
                            detectDragGestures(onDragEnd = {}) { change, _ ->
                                val day = first + (change.position.x.coerceIn(0f, size.width.toFloat()) / size.width * days).toLong()
                                picked = points.indices.minByOrNull { abs(points[it].day.toEpochDay() - day) }
                            }
                        },
                ) {
                    fun at(p: ValuePoint) = Offset(
                        (p.day.toEpochDay() - first).toFloat() / days * size.width,
                        size.height - ((p.value - min) / span).toFloat() * size.height * 0.9f - size.height * 0.05f,
                    )
                    for (i in 0..3) {
                        val y = size.height * i / 3f
                        drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    }
                    val path = Path()
                    points.forEachIndexed { i, p -> at(p).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
                    drawPath(path, line, style = Stroke(width = 3.dp.toPx()))
                    picked?.let { i ->
                        val o = at(points[i])
                        drawLine(grid, Offset(o.x, 0f), Offset(o.x, size.height), strokeWidth = 2f)
                        drawCircle(line, radius = 5.dp.toPx(), center = o)
                    }
                }
                Column(Modifier.height(180.dp).padding(start = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(Fmt.money(max), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Fmt.money(min), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text(shortDate(points.first().day), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(shortDate(points.last().day), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
