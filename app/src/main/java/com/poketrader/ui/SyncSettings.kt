package com.poketrader.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poketrader.container
import com.poketrader.data.FirstChoice
import com.poketrader.data.FirstSync
import com.poketrader.data.FolderListing
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.style.TextOverflow
import com.poketrader.data.NextcloudAccount
import com.poketrader.data.NextcloudClient
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/** The "Sync with Nextcloud" part of Settings. */
@Composable
fun SyncSection() {
    val c = LocalContext.current.container
    val s by c.sync.status.collectAsStateWithLifecycle()
    var connecting by rememberSaveable { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text("Sync with Nextcloud", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    if (!s.connected) {
        Text(
            "Keep your collection, binders, wishlist, trades, scans and preferences the same on both your phones, through your " +
                "own Nextcloud. Prices and pictures aren't synced: each phone downloads those itself.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { connecting = true }) { Text("Connect to Nextcloud") }
    } else {
        Text("Connected as ${s.user} on ${Uri.parse(s.server).host ?: s.server}")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Folder: /${s.folder}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = c.sync::changeFolder, enabled = !s.running) { Text("Change") }
        }
        when {
            s.running -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Syncing…", color = MaterialTheme.colorScheme.primary)
            }
            s.lastSyncAt > 0 -> Text("Last synced: ${Fmt.dateTime(s.lastSyncAt)}")
            !s.ready -> Text("Not synced yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        s.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        s.olderPhone?.let { older ->
            Spacer(Modifier.height(4.dp))
            Text(olderPhoneNote(older.name), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = c.sync::forgetOlderPhone, contentPadding = PaddingValues(0.dp)) { Text("I don't use that phone any more") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.ready) {
                Button(onClick = { c.sync.syncNow() }, enabled = !s.running) { Text("Sync now") }
            } else {
                Button(onClick = { c.appScope.launch { c.sync.prepareFirst() } }, enabled = !s.running && s.firstChoice == null) {
                    Text("Start syncing")
                }
            }
            OutlinedButton(onClick = { confirmDisconnect = true }, enabled = !s.running) { Text("Disconnect") }
        }
        Spacer(Modifier.height(12.dp))
        SwitchRow(
            title = "Sync automatically",
            body = "When you open the app, shortly after a change, when you leave the app and every hour.",
            checked = s.autoSync,
            onChange = c.sync::setAutoSync,
        )
        SwitchRow(
            title = "Only on Wi-Fi",
            body = "Automatic syncs wait for Wi-Fi, so they don't use mobile data. “Sync now” always works.",
            checked = s.wifiOnly,
            enabled = s.autoSync,
            onChange = c.sync::setWifiOnly,
        )
    }

    if (connecting) ConnectDialog(onDone = { connecting = false })
    if (s.choosingFolder && !connecting) FolderPickerDialog(start = if (s.ready) s.folder else "", firstTime = !s.ready)
    s.firstChoice?.let { choice ->
        FirstSyncDialog(choice, onChoose = c.sync::chooseFirst, onLater = c.sync::cancelFirstChoice)
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect from Nextcloud?") },
            text = { Text("This phone stops syncing. Its data stays on the phone, and the copy on Nextcloud stays there for your other phone.") },
            confirmButton = {
                TextButton(onClick = { confirmDisconnect = false; scope.launch { c.sync.disconnect() } }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConnectDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var server by rememberSaveable { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf(false) }
    var user by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var login by remember { mutableStateOf<NextcloudClient.LoginStart?>(null) }

    /** Checks the account and does the first sync, in the app scope so leaving the screen doesn't stop it. */
    fun connect(account: NextcloudAccount, fromLoginFlow: Boolean) {
        busy = "Connecting…"
        scope.launch {
            val r = c.appScope.async { runCatching { c.sync.connect(account, fromLoginFlow) } }.await()
            busy = null
            r.onSuccess { onDone() }.onFailure { error = message(it) }
        }
    }

    // Browser login: ask Nextcloud every couple of seconds whether the user has approved it yet (for up to 20 minutes).
    LaunchedEffect(login) {
        val start = login ?: return@LaunchedEffect
        repeat(600) {
            delay(2_000)
            val account = runCatching { c.sync.pollLogin(start) }.getOrNull()
            if (account != null) {
                login = null
                connect(account, fromLoginFlow = true)
                return@LaunchedEffect
            }
        }
        login = null
        busy = null
        error = "The login wasn't approved in time. Try again."
    }

    AlertDialog(
        onDismissRequest = { if (busy == null) onDone() },
        title = { Text("Connect to Nextcloud") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it; error = null },
                    label = { Text("Server address") },
                    placeholder = { Text("cloud.example.com") },
                    singleLine = true,
                    enabled = busy == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (manual) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = user, onValueChange = { user = it; error = null }, label = { Text("User name") },
                        singleLine = true, enabled = busy == null, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it; error = null }, label = { Text("App password") },
                        singleLine = true, enabled = busy == null, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Make one in Nextcloud under Settings → Security → Devices & sessions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "You'll log in to Nextcloud in your browser and allow Poké Trader access. The app gets its own app " +
                            "password, which you can revoke in Nextcloud at any time; your own password never reaches the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                busy?.let {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.primary)
                    }
                }
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy == null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (manual) "Log in with the browser instead" else "Use an app password instead",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { manual = !manual; error = null }.padding(vertical = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = busy == null && server.isNotBlank() && (!manual || (user.isNotBlank() && password.isNotBlank())),
                onClick = {
                    val url = NextcloudClient.normalizeServer(server)
                    if (url == null) { error = "That doesn't look like a server address."; return@TextButton }
                    if (manual) {
                        connect(NextcloudAccount(url, user.trim(), password.trim()), fromLoginFlow = false)
                    } else {
                        busy = "Opening the login page…"
                        scope.launch {
                            runCatching { c.sync.startLogin(url) }
                                .onSuccess {
                                    busy = "Approve Poké Trader in the browser, then come back here."
                                    login = it
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.loginUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }.onFailure { busy = null; login = null; error = "No browser found to log in with." }
                                }
                                .onFailure { busy = null; error = message(it) }
                        }
                    }
                },
            ) { Text(if (manual) "Connect" else "Log in") }
        },
        dismissButton = {
            TextButton(onClick = {
                login = null
                busy = null
                onDone()
            }) { Text("Cancel") }
        },
    )
}

/**
 * Picks the Nextcloud folder for the sync file: browse into folders, make a new one, then "Use this
 * folder". Both phones must use the same one. Since 1.20 (as MTG Trader's).
 */
@Composable
private fun FolderPickerDialog(start: String, firstTime: Boolean) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var path by rememberSaveable { mutableStateOf(start) }
    var listing by remember { mutableStateOf<FolderListing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    fun child(name: String) = if (path.isEmpty()) name else "$path/$name"
    LaunchedEffect(path, reload) {
        listing = null
        error = null
        runCatching { c.sync.listFolder(path) }.onSuccess { listing = it }.onFailure { error = message(it) }
    }
    AlertDialog(
        onDismissRequest = c.sync::cancelFolderChoice,
        title = { Text("Folder for the sync file") },
        text = {
            Column {
                Text(
                    "Pick the same folder on both your phones. Not the MTG Trader folder: each app needs its own." + if (firstTime) " Without a choice, the app uses “${NextcloudClient.FOLDER}”." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("/$path", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                if (path.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().clickable { path = path.substringBeforeLast('/', "") }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.ArrowUpward, null)
                        Spacer(Modifier.width(12.dp))
                        Text("Up")
                    }
                }
                val l = listing
                when {
                    error != null -> Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    l == null -> Row(Modifier.padding(vertical = 12.dp)) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                    else -> {
                        LazyColumn(Modifier.heightIn(max = 280.dp)) {
                            items(l.folders) { name ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { path = child(name) }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (l.folders.isEmpty()) Text("No folders in here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (l.hasSyncFile) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "This folder already holds a sync file (from your other phone?). Choosing it syncs with that data; if it's MTG Trader's, pick another folder.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                TextButton(onClick = { naming = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
                    Icon(Icons.Default.CreateNewFolder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New folder here")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { c.appScope.launch { c.sync.useFolder(path) } }, enabled = listing != null) { Text("Use this folder") }
        },
        dismissButton = {
            TextButton(onClick = c.sync::cancelFolderChoice) { Text(if (firstTime) "Use “${NextcloudClient.FOLDER}”" else "Cancel") }
        },
    )
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace("/", "") },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    naming = false
                    val target = child(name.trim())
                    scope.launch {
                        runCatching { c.sync.createFolder(target) }
                            .onSuccess { path = target; reload++ }
                            .onFailure { error = message(it) }
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

private fun message(e: Throwable): String = when (e) {
    is IOException -> "Couldn't reach the server: ${e.message ?: e.javaClass.simpleName}"
    else -> e.message ?: "Something went wrong"
}

@Composable
private fun FirstSyncDialog(choice: FirstChoice, onChoose: (FirstSync) -> Unit, onLater: () -> Unit) {
    var pick by rememberSaveable { mutableStateOf(FirstSync.MERGE) }
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Both have data") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("This phone: ${choice.phone}\nNextcloud: ${choice.nextcloud}")
                Spacer(Modifier.height(8.dp))
                FirstSync.entries.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { pick = f }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = pick == f, onClick = { pick = f })
                        Column {
                            Text(f.label)
                            Text(f.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(pick) }) { Text("Start syncing") } },
        dismissButton = { TextButton(onClick = onLater) { Text("Later") } },
    )
}

/** The note in Settings while [name] still runs an older version of the sync file. */
internal fun olderPhoneNote(name: String) =
    "“$name” has an older version of Poké Trader: it can't sync until you update the app there. Its changes stay on that phone and sync after the update."

/**
 * Shown once, anywhere in the app, when Nextcloud sync finds the other phone still on an older version
 * of the sync file.
 */
@Composable
fun OlderPhoneDialog() {
    val c = LocalContext.current.container
    val s by c.sync.status.collectAsStateWithLifecycle()
    val older = s.olderPhone?.takeUnless { it.acknowledged } ?: return
    AlertDialog(
        onDismissRequest = c.sync::acknowledgeOlderPhone,
        title = { Text("Update Poké Trader on your other phone") },
        text = {
            Text(
                "“${older.name}” last synced with an older version of Poké Trader, which can't read what this version writes, " +
                    "so that phone stops syncing and asks to be updated.\n\n" +
                    "Nothing is lost: what changed on it syncs as soon as it has the new version.",
            )
        },
        confirmButton = { TextButton(onClick = c.sync::acknowledgeOlderPhone) { Text("OK") } },
    )
}
