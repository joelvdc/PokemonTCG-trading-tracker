package com.poketrader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poketrader.data.ImageKey
import com.poketrader.data.PriceType
import com.poketrader.data.WishlistItem
import com.poketrader.data.WishlistOwned
import com.poketrader.data.WishlistRow

/** Edits a wishlist entry: how many, whether any version of the card will do, notes. Since 1.9. */
@Composable
fun WishlistDialog(
    row: WishlistRow,
    owned: WishlistOwned?,
    priceType: PriceType,
    onDismiss: () -> Unit,
    onSave: (WishlistItem) -> Unit,
    onDelete: () -> Unit,
) {
    val card = row.item.card
    var item by remember { mutableStateOf(row.item) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(card.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CardImage(
                        card.thumbUrl, Modifier.width(110.dp), enlargeUrl = card.largeUrl, placeholder = "#" + card.numberLabel,
                        fallbackKey = ImageKey(card.cardId, card.dataLang, card.variantId),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(card.setName, style = MaterialTheme.typography.bodyMedium)
                        Text("#${card.numberLabel} · ${card.variantLabel}", style = MaterialTheme.typography.bodySmall)
                        owned?.let {
                            Text(
                                if (it.owned > 0) "You have ${it.owned}" + (if (it.gotSince > 0) " (${it.gotSince} got since adding it)" else "") else "Not in your collection yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cards wanted", Modifier.weight(1f))
                    QuantityStepper(item.quantity, { item = item.copy(quantity = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Any version will do")
                        Text(
                            if (item.anyVariant) "Normal, reverse holo… all count" else "Only ${card.variantLabel.lowercase()} counts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = item.anyVariant, onCheckedChange = { item = item.copy(anyVariant = it) })
                }
                OutlinedTextField(
                    value = item.notes ?: "",
                    onValueChange = { item = item.copy(notes = it.ifBlank { null }) },
                    label = { Text("Notes") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                PriceTable(pricesOf(card, row.price), priceType)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(item) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
