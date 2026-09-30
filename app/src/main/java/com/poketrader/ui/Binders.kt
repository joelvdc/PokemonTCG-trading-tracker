package com.poketrader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poketrader.container
import com.poketrader.data.Binder
import com.poketrader.data.BinderChoice

private const val NEW_BINDER = -1L

/** All binders, kept up to date. */
@Composable
fun rememberBinders(): List<Binder> {
    val c = LocalContext.current.container
    val binders by remember { c.db.binderDao().observeAll() }.collectAsStateWithLifecycle(emptyList())
    return binders
}

fun binderName(id: Long, binders: List<Binder>): String =
    if (id == Binder.UNSORTED) Binder.UNSORTED_NAME else binders.firstOrNull { it.id == id }?.name ?: Binder.UNSORTED_NAME

/**
 * Chooses where cards go: Unsorted, an existing binder, or a new binder (named in a text field,
 * starting from [suggestedName]).
 */
@Composable
fun BinderPicker(label: String, choice: BinderChoice, onChange: (BinderChoice) -> Unit, suggestedName: String = "", modifier: Modifier = Modifier) {
    val binders = rememberBinders()
    val options = listOf(Binder.UNSORTED) + binders.map { it.id } + NEW_BINDER
    val selected = if (choice.newName != null) NEW_BINDER else choice.binderId
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DropdownSelector(
            label = label,
            selected = selected,
            options = options,
            optionLabel = { id -> if (id == NEW_BINDER) "New binder…" else binderName(id, binders) },
            onSelect = { id -> onChange(if (id == NEW_BINDER) BinderChoice(newName = choice.newName ?: suggestedName) else BinderChoice(id)) },
            modifier = Modifier.fillMaxWidth(),
        )
        choice.newName?.let { name ->
            OutlinedTextField(
                value = name,
                onValueChange = { onChange(BinderChoice(newName = it)) },
                label = { Text("New binder name") },
                singleLine = true,
                isError = name.isBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

val BinderChoice.isValid get() = newName == null || newName.isNotBlank()

/** Asks for a binder name, for creating ([binder] null) or renaming a binder. */
@Composable
fun BinderNameDialog(binder: Binder?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val c = LocalContext.current.container
    var name by remember { mutableStateOf(binder?.name ?: "") }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(name) { problem = if (name.isBlank()) null else c.repo.binderNameProblem(name, binder?.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (binder == null) "New binder" else "Rename binder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                isError = problem != null,
                supportingText = { problem?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank() && problem == null) {
                Text(if (binder == null) "Create" else "Rename")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Moves all cards of binder [from] (or Unsorted) into another binder. */
@Composable
fun MergeBinderDialog(from: Long, cardCount: Int, onDismiss: () -> Unit, onMerge: (into: Long, deleteSource: Boolean) -> Unit) {
    val binders = rememberBinders()
    val targets = (listOf(Binder.UNSORTED) + binders.map { it.id }).filter { it != from }
    var into by remember { mutableStateOf(targets.firstOrNull()) }
    // The binder list arrives a moment after the dialog opens.
    LaunchedEffect(targets) { if (into == null || into !in targets) into = targets.firstOrNull() }
    var deleteSource by remember { mutableStateOf(true) }
    val fromName = binderName(from, binders)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (from == Binder.UNSORTED) "Move all Unsorted cards" else "Merge “$fromName”") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 420.dp)) {
                Text(
                    "Move its $cardCount card(s) into:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (targets.isEmpty()) {
                    Text("Create another binder first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                targets.forEach { id ->
                    Row(Modifier.fillMaxWidth().clickable { into = id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = into == id, onClick = { into = id })
                        Text(binderName(id, binders))
                    }
                }
                if (from != Binder.UNSORTED) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { deleteSource = !deleteSource }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = deleteSource, onCheckedChange = { deleteSource = it })
                        Text("Delete “$fromName” afterwards")
                    }
                }
                Text(
                    "Identical cards (same card, version, condition and language) are combined.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { into?.let { onMerge(it, deleteSource) } }, enabled = into != null) {
                Text(if (from == Binder.UNSORTED) "Move" else "Merge")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DeleteBinderDialog(binder: Binder, cardCount: Int, onDismiss: () -> Unit, onDelete: (deleteCards: Boolean) -> Unit) {
    var deleteCards by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete “${binder.name}”?") },
        text = {
            Column {
                if (cardCount > 0) {
                    Text("It holds $cardCount card(s).", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth().clickable { deleteCards = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !deleteCards, onClick = { deleteCards = false })
                        Text("Keep the cards (move them to ${Binder.UNSORTED_NAME})")
                    }
                    Row(Modifier.fillMaxWidth().clickable { deleteCards = true }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = deleteCards, onClick = { deleteCards = true })
                        Text("Remove the cards from the collection too")
                    }
                } else {
                    Text("The binder is empty.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDelete(deleteCards) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A checkbox row with a label, e.g. "Keep them in the scan list". */
@Composable
fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
