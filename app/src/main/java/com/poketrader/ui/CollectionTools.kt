package com.poketrader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.poketrader.data.CONDITIONS
import com.poketrader.data.CollectionFilter
import com.poketrader.data.PrintFilter
import com.poketrader.data.SortField
import com.poketrader.data.SortLevel
import com.poketrader.data.SortSpec

private val PRESETS = listOf(
    "Name" to SortSpec(listOf(SortLevel(SortField.NAME))),
    "Set and number" to SortSpec(listOf(SortLevel(SortField.NUMBER))),
    "Rarest, then name" to SortSpec(listOf(SortLevel(SortField.RARITY), SortLevel(SortField.NAME))),
    "Most valuable" to SortSpec(listOf(SortLevel(SortField.VALUE))),
    "Newest" to SortSpec(listOf(SortLevel(SortField.RECENT))),
)

/** Sorting in layers ("Rarity, then Name"), each with its own direction. Since 1.11. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SortDialog(initial: SortSpec, onDismiss: () -> Unit, onSave: (SortSpec) -> Unit) {
    var levels by remember { mutableStateOf(initial.levels) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PRESETS.forEach { (label, spec) ->
                        FilterChip(selected = levels == spec.levels, onClick = { levels = spec.levels }, label = { Text(label) })
                    }
                }
                HorizontalDivider()
                levels.forEachIndexed { i, level ->
                    Text(if (i == 0) "Sort by" else "then by", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DropdownSelector(
                            "Field", level.field, SortField.entries.filter { f -> f == level.field || levels.none { it.field == f } }, { it.label },
                            { f -> levels = levels.toMutableList().also { it[i] = SortLevel(f) } }, Modifier.weight(1f),
                        )
                        if (levels.size > 1) IconButton(onClick = { levels = levels.toMutableList().also { it.removeAt(i) } }) { Icon(Icons.Default.Close, "Remove level") }
                    }
                    DropdownSelector(
                        "Order", level.reversed, listOf(false, true), { if (it) level.field.backward else level.field.forward },
                        { r -> levels = levels.toMutableList().also { it[i] = level.copy(reversed = r) } }, Modifier.fillMaxWidth(),
                    )
                }
                if (levels.size < 3) {
                    TextButton(onClick = {
                        val next = SortField.entries.first { f -> levels.none { it.field == f } }
                        levels = levels + SortLevel(next)
                    }) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add a level")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(SortSpec(levels)) }) { Text("Sort") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A set the collection holds cards of, for the filter's set list. */
data class OwnedSet(val id: String, val name: String, val cards: Int)

/**
 * The filter button's dialog: rarity, set, version, print, condition, language and value, with
 * only the values the collection actually has. [count] shows how many cards a filter would leave.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterDialog(
    initial: CollectionFilter,
    sets: List<OwnedSet>,
    rarities: List<String>,
    variants: List<String>,
    languages: List<String>,
    count: (CollectionFilter) -> Int,
    onDismiss: () -> Unit,
    onApply: (CollectionFilter) -> Unit,
) {
    var f by remember { mutableStateOf(initial) }
    var setQuery by remember { mutableStateOf("") }
    var minText by remember { mutableStateOf(initial.minPrice?.let { "%.2f".format(it) } ?: "") }
    var maxText by remember { mutableStateOf(initial.maxPrice?.let { "%.2f".format(it) } ?: "") }
    fun <T> Set<T>.toggle(v: T) = if (v in this) this - v else this + v
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (rarities.isNotEmpty()) {
                    Part("Rarity")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rarities.forEach { r -> FilterChip(selected = r in f.rarities, onClick = { f = f.copy(rarities = f.rarities.toggle(r)) }, label = { Text(r) }) }
                    }
                }
                Part("Set")
                if (f.sets.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        f.sets.forEach { id ->
                            InputChip(
                                selected = true, onClick = { f = f.copy(sets = f.sets - id) },
                                label = { Text(sets.firstOrNull { it.id == id }?.name ?: id) },
                                trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                SearchField(setQuery, { setQuery = it }, "Find a set", Modifier.fillMaxWidth())
                val q = setQuery.trim()
                if (q.isNotEmpty()) {
                    sets.filter { (it.name.contains(q, true) || it.id.equals(q, true)) && it.id !in f.sets }.take(8).forEach { s ->
                        Text(
                            "${s.name} · ${s.cards}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().clickable { f = f.copy(sets = f.sets + s.id); setQuery = "" }.padding(vertical = 8.dp),
                        )
                    }
                }
                if (variants.size > 1) {
                    Part("Version")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        variants.forEach { v -> FilterChip(selected = v in f.variants, onClick = { f = f.copy(variants = f.variants.toggle(v)) }, label = { Text(v) }) }
                    }
                }
                Part("Print")
                PrintFilter.entries.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { f = f.copy(print = p) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = f.print == p, onClick = { f = f.copy(print = p) })
                        Text(p.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Part("Condition")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CONDITIONS.forEach { (k, _) -> FilterChip(selected = k in f.conditions, onClick = { f = f.copy(conditions = f.conditions.toggle(k)) }, label = { Text(k) }) }
                }
                if (languages.size > 1) {
                    Part("Language")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        languages.forEach { l -> FilterChip(selected = l in f.languages, onClick = { f = f.copy(languages = f.languages.toggle(l)) }, label = { Text(l) }) }
                    }
                }
                Part("Value per card (€)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minText, onValueChange = { minText = it; f = f.copy(minPrice = Fmt.parseMoney(it)) }, label = { Text("From") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = maxText, onValueChange = { maxText = it; f = f.copy(maxPrice = Fmt.parseMoney(it)) }, label = { Text("To") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(f) }) { Text("Show ${"%,d".format(count(f))} cards") } },
        dismissButton = {
            Row {
                TextButton(onClick = { f = CollectionFilter(); minText = ""; maxText = "" }, enabled = !f.isEmpty) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Part(title: String) {
    Spacer(Modifier.height(4.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** The active filter, as removable chips under the search field. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActiveFilterChips(f: CollectionFilter, sets: List<OwnedSet>, onChange: (CollectionFilter) -> Unit, onEdit: () -> Unit) {
    val chips = buildList<Pair<String, CollectionFilter>> {
        if (f.rarities.isNotEmpty()) add(f.rarities.joinToString(", ") to f.copy(rarities = emptySet()))
        if (f.sets.isNotEmpty()) add(f.sets.joinToString(", ") { id -> sets.firstOrNull { it.id == id }?.name ?: id } to f.copy(sets = emptySet()))
        if (f.variants.isNotEmpty()) add(f.variants.joinToString(", ") to f.copy(variants = emptySet()))
        if (f.print != PrintFilter.ANY) add(f.print.label to f.copy(print = PrintFilter.ANY))
        if (f.conditions.isNotEmpty()) add(f.conditions.joinToString(", ") to f.copy(conditions = emptySet()))
        if (f.languages.isNotEmpty()) add(f.languages.joinToString(", ") to f.copy(languages = emptySet()))
        if (f.minPrice != null || f.maxPrice != null) {
            add(
                when {
                    f.maxPrice == null -> "€%.2f and up".format(f.minPrice)
                    f.minPrice == null -> "Up to €%.2f".format(f.maxPrice)
                    else -> "€%.2f–€%.2f".format(f.minPrice, f.maxPrice)
                } to f.copy(minPrice = null, maxPrice = null)
            )
        }
    }
    if (chips.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { (label, without) ->
            InputChip(
                selected = true, onClick = onEdit, label = { Text(label, maxLines = 1) },
                trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp).clickable { onChange(without) }) },
            )
        }
        if (chips.size > 1) AssistChip(onClick = { onChange(CollectionFilter()) }, label = { Text("Clear all") })
    }
}
