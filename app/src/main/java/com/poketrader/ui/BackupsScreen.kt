package com.poketrader.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.poketrader.container
import com.poketrader.data.BackupDiff
import com.poketrader.data.BackupFile
import com.poketrader.data.BackupInfo
import com.poketrader.data.BackupPlace
import com.poketrader.data.BackupReason
import com.poketrader.data.BackupRetention
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Restore points: making them, the list, and restoring one. Since 1.20 (as MTG Trader's). */
@Composable
fun BackupsScreen(nav: NavController) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val working by c.backups.working.collectAsStateWithLifecycle()
    val lastError by c.backups.lastError.collectAsStateWithLifecycle()
    val changed by c.backups.changed.collectAsStateWithLifecycle()
    val syncStatus by c.sync.status.collectAsStateWithLifecycle()
    val onNextcloud = syncStatus.connected && syncStatus.ready
    var list by remember { mutableStateOf<List<BackupInfo>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var alsoOnPhone by remember { mutableStateOf(c.backups.alsoOnPhone) }
    var folder by remember { mutableStateOf(c.backups.folder) }
    var opened by remember { mutableStateOf<BackupInfo?>(null) }
    var preview by remember { mutableStateOf<Pair<BackupFile, BackupDiff>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    LaunchedEffect(changed, onNextcloud, folder) {
        val (l, n) = c.backups.list()
        list = l
        note = n
    }

    fun openForRestore(load: suspend () -> BackupFile) {
        scope.launch {
            busy = "Reading the backup…"
            try {
                val file = load()
                preview = file to c.backups.diff(file)
            } catch (e: Exception) {
                toast(e.message ?: "Couldn't read that backup")
            } finally {
                busy = null
            }
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        if (uri != null) scope.launch {
            try {
                val bytes = c.backups.exportBytes()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
                toast("Backup file saved")
            } catch (e: Exception) {
                toast("Couldn't save it: ${e.message}")
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openForRestore { c.backups.readUri(uri) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            c.backups.folder = uri
            folder = uri
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Backups") },
                navigationIcon = { IconButton(onClick = { nav.safePopBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "intro") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "A backup holds your collection, binders, trades, scanned cards, wishlist and settings, so you can go back to it if something goes wrong. " +
                            "The app makes one each day when something changed, and before big changes: the first Nextcloud sync, CSV imports, deleting selected cards " +
                            "and restoring a backup.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (onNextcloud) "Backups go to the “Backups” folder in your Nextcloud sync folder (“${syncStatus.folder.ifEmpty { "/" }}”), where both your phones can use them."
                        else if (folder != null) "Backups are saved in the folder you picked on this phone."
                        else "Backups are saved in the app's storage on this phone. They're lost if you uninstall the app: pick a folder below, or connect Nextcloud sync, to keep them safe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Kept: the latest ${BackupRetention.KEEP_LATEST} automatic backups and one per week for ${BackupRetention.KEEP_WEEKS} weeks. " +
                            "Backups you make yourself stay until you delete them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    (working ?: busy)?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                try {
                                    val places = c.backups.create(BackupReason.MANUAL)
                                    toast("Backup made (${places.joinToString(" and ") { it.label }})")
                                } catch (e: Exception) {
                                    toast("The backup failed: ${e.message}")
                                }
                            }
                        }, enabled = working == null) { Text("Back up now") }
                        OutlinedButton(onClick = { exporter.launch(c.backups.suggestedFileName()) }) { Text("Save to a file…") }
                    }
                    OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Restore from a file…") }

                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    if (onNextcloud) {
                        SwitchRow(
                            title = "Also keep backups on this phone",
                            body = "Besides Nextcloud, in case it can't be reached when you need a backup.",
                            checked = alsoOnPhone,
                            onChange = { alsoOnPhone = it; c.backups.alsoOnPhone = it },
                        )
                    }
                    Text("Backups on this phone", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (folder == null) "In the app's storage." else "In the folder you picked (they stay when the app is uninstalled).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { folderPicker.launch(null) }) { Text(if (folder == null) "Choose a folder…" else "Change folder…") }
                        if (folder != null) TextButton(onClick = { c.backups.folder = null; folder = null }) { Text("Use the app's storage") }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Restore points", style = MaterialTheme.typography.titleMedium)
                    note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            val l = list
            when {
                l == null -> item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                l.isEmpty() -> item(key = "none") {
                    Text("No backups yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> items(l, key = { it.place.name + it.ref }) { b ->
                    Row(
                        Modifier.fillMaxWidth().clickable { opened = b }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            when (b.place) {
                                BackupPlace.NEXTCLOUD -> Icons.Default.Cloud
                                BackupPlace.PHONE -> Icons.Default.PhoneAndroid
                                BackupPlace.FOLDER -> Icons.Default.Folder
                            },
                            b.place.label,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(Fmt.dateTime(b.createdAt), fontWeight = if (b.manual) FontWeight.SemiBold else null)
                            Text(
                                b.reason + (if (b.manual) " · kept" else "") + " · " + size(b.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    opened?.let { b ->
        AlertDialog(
            onDismissRequest = { opened = null },
            title = { Text(Fmt.dateTime(b.createdAt)) },
            text = { Text("${b.reason} · ${b.place.label} · ${size(b.size)}") },
            confirmButton = {
                TextButton(onClick = {
                    opened = null
                    openForRestore { c.backups.read(b) }
                }) { Text("Restore…") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        opened = null
                        scope.launch { runCatching { c.backups.delete(b) }.onFailure { toast("Couldn't delete it: ${it.message}") } }
                    }) { Text("Delete") }
                    TextButton(onClick = { opened = null }) { Text("Close") }
                }
            },
        )
    }

    preview?.let { (file, diff) ->
        RestoreDialog(file, diff, onDismiss = { preview = null }) {
            preview = null
            scope.launch {
                try {
                    c.backups.restore(file)
                    toast("Restored. A backup of what you had was made first.")
                } catch (e: Exception) {
                    toast("Restoring failed: ${e.message}")
                }
            }
        }
    }
}

@Composable
private fun RestoreDialog(file: BackupFile, diff: BackupDiff, onDismiss: () -> Unit, onRestore: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore this backup?") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    listOfNotNull(
                        file.createdAt.takeIf { it > 0 }?.let { Fmt.dateTime(it) },
                        file.reason.takeIf { it.isNotEmpty() },
                        file.device.takeIf { it.isNotEmpty() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (diff.same) {
                    Text("It holds the same as the app now.")
                } else {
                    Text("Now → in the backup:", fontWeight = FontWeight.SemiBold)
                    Text("Cards: ${diff.cardsNow} → ${diff.cardsThen}")
                    if (diff.bindersNow != diff.bindersThen) Text("Binders: ${diff.bindersNow} → ${diff.bindersThen}")
                    if (diff.tradesNow != diff.tradesThen) Text("Trades: ${diff.tradesNow} → ${diff.tradesThen}")
                    if (diff.wishlistNow != diff.wishlistThen) Text("Wishlist: ${diff.wishlistNow} → ${diff.wishlistThen}")
                    if (diff.cardChanges.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Cards that differ (${diff.cardChanges.size}):", fontWeight = FontWeight.SemiBold)
                        diff.cardChanges.take(60).forEach { (name, n) ->
                            Text((if (n > 0) "+$n " else "−${-n} ") + name, style = MaterialTheme.typography.bodySmall)
                        }
                        if (diff.cardChanges.size > 60) Text("…and ${diff.cardChanges.size - 60} more", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Everything in the app is replaced by the backup. A backup of what you have now is made first. " +
                        "With Nextcloud sync, your other phone gets the restored data too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onRestore, enabled = !diff.same) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun size(bytes: Long): String = when {
    bytes <= 0 -> "?"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
