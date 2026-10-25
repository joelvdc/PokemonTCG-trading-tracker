package com.poketrader.data

import android.content.Context
import android.os.Build
import androidx.room.InvalidationTracker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.poketrader.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the Settings screen shows about sync. */
data class SyncStatus(
    val server: String? = null,
    val user: String? = null,
    val running: Boolean = false,
    val lastSyncAt: Long = 0,
    val lastError: String? = null,
    /** Set up and synced at least once; until then automatic sync waits. */
    val ready: Boolean = false,
    /** Connected, but both this phone and Nextcloud hold data: the user picks a [FirstSync] first. */
    val firstChoice: FirstChoice? = null,
    val autoSync: Boolean = true,
    val wifiOnly: Boolean = false,
    /** The Nextcloud folder holding the sync file. */
    val folder: String = NextcloudClient.FOLDER,
    /** The user is picking the folder (right after connecting, or to change it). */
    val choosingFolder: Boolean = false,
    /** Another phone that last synced with a version too old to read this one's file. */
    val olderPhone: OlderPhone? = null,
) {
    val connected get() = server != null
}

data class FirstChoice(val phone: SyncSummary, val nextcloud: SyncSummary)

/**
 * A phone ([name]) seen syncing with an older version of the sync file: it can't sync until it's
 * updated. [acknowledged] once the owner has seen the warning.
 */
data class OlderPhone(val name: String, val seenAt: Long, val acknowledged: Boolean = false)

/** What a copy of the data holds, to help pick a [FirstSync]. */
data class SyncSummary(val cards: Int, val binders: Int, val wishlist: Int, val trades: Int, val scans: Int) {
    override fun toString() = listOfNotNull(
        count(cards, "card"),
        binders.takeIf { it > 0 }?.let { count(it, "binder") },
        wishlist.takeIf { it > 0 }?.let { count(it, "wishlist card") },
        trades.takeIf { it > 0 }?.let { count(it, "trade") },
        scans.takeIf { it > 0 }?.let { count(it, "scanned card") },
    ).joinToString(", ")

    private fun count(n: Int, what: String) = "$n $what" + if (n == 1) "" else "s"

    companion object {
        fun of(d: SyncData) = SyncSummary(d.collection.sumOf { it.item.quantity }, d.binders.size, d.wishlist.sumOf { it.quantity }, d.trades.size, d.scans.sumOf { it.quantity })
    }
}

/**
 * Keeps this phone in sync with the others through one file on the owner's Nextcloud. With
 * automatic sync on, it syncs when the app opens, ~30 s after a change, when the app goes to
 * the background and every hour (only on Wi-Fi if chosen). "Sync now" always runs. Since 1.20
 * (as MTG Trader's).
 */
class SyncManager(
    private val context: Context,
    private val db: AppDatabase,
    private val store: SyncStore,
    private val client: NextcloudClient,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var debounce: Job? = null
    private var changingFolder = false

    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<SyncStatus> = _status

    init {
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(arrayOf("collection", "binders", "trades", "trade_items", "scans", "wishlist", "sync_deletions")) {
            override fun onInvalidated(tables: Set<String>) = onDataChanged()
        })
    }

    private fun readStatus() = SyncStatus(
        // Not connected if the password can't be read (e.g. app data restored onto another phone).
        server = prefs.getString(K_SERVER, null)?.takeIf { account() != null },
        user = prefs.getString(K_USER, null),
        lastSyncAt = prefs.getLong(K_LAST_SYNC, 0),
        lastError = prefs.getString(K_ERROR, null),
        ready = prefs.getBoolean(K_FIRST_DONE, false),
        autoSync = prefs.getBoolean(K_AUTO, true),
        wifiOnly = prefs.getBoolean(K_WIFI, false),
        folder = prefs.getString(K_FOLDER, null) ?: NextcloudClient.FOLDER,
        // Connected but the folder was never chosen (and never synced): ask.
        choosingFolder = account() != null && (changingFolder || (!prefs.getBoolean(K_FIRST_DONE, false) && !prefs.contains(K_FOLDER))),
        olderPhone = prefs.getString(K_OLDER, null)?.let { OlderPhone(it, prefs.getLong(K_OLDER_AT, 0), prefs.getBoolean(K_OLDER_SEEN, false)) },
    )

    private fun account(): NextcloudAccount? {
        val server = prefs.getString(K_SERVER, null) ?: return null
        val login = prefs.getString(K_USER, null) ?: return null
        val pw = prefs.getString(K_PASSWORD, null)?.let(SecretStore::decrypt) ?: return null
        return NextcloudAccount(server, login, pw, prefs.getString(K_USER_ID, null) ?: login, prefs.getString(K_FOLDER, null) ?: NextcloudClient.FOLDER)
    }

    private val firstDone get() = prefs.getBoolean(K_FIRST_DONE, false)

    /** The account, once this phone has synced with it (so its folder holds this app's data). */
    fun readyAccount(): NextcloudAccount? = if (firstDone) account() else null

    /** Runs before the first sync with a folder changes anything on this phone (a backup). */
    var beforeFirstSync: (suspend () -> Unit)? = null

    /** Whether an automatic sync may run now. */
    private fun autoAllowed(): Boolean {
        val s = _status.value
        return s.connected && firstDone && s.autoSync && (!s.wifiOnly || network.onUnmeteredNetwork())
    }

    // ---- Connecting ------------------------------------------------------------------------

    suspend fun startLogin(server: String) = client.startLogin(server)
    suspend fun pollLogin(start: NextcloudClient.LoginStart) = client.pollLogin(start)

    /** Saves the account (after checking it works); then the user picks the folder ([useFolder]) and the first sync follows. */
    suspend fun connect(account: NextcloudAccount, fromLoginFlow: Boolean) {
        val checked = client.verify(account)
        prefs.edit()
            .putString(K_SERVER, checked.server)
            .putString(K_USER, checked.login)
            .putString(K_USER_ID, checked.userId)
            .putString(K_PASSWORD, SecretStore.encrypt(checked.password))
            .putBoolean(K_REVOKE, fromLoginFlow)
            .putBoolean(K_FIRST_DONE, false)
            .remove(K_ETAG).remove(K_STAMP).remove(K_PREFS_AT).remove(K_ERROR).remove(K_LAST_SYNC).remove(K_FOLDER)
            .apply()
        _status.value = readStatus()
        schedule()
    }

    // ---- The folder ------------------------------------------------------------------------

    suspend fun listFolder(path: String): FolderListing = client.list(account() ?: throw SyncException("Not connected"), path)

    suspend fun createFolder(path: String) = client.makeFolder(account() ?: throw SyncException("Not connected"), path)

    /** Opens the folder picker to move syncing to another folder. */
    fun changeFolder() {
        changingFolder = true
        _status.value = readStatus()
    }

    /**
     * Syncs through [path] from now on. Like connecting anew: when both this phone and the
     * folder hold data, [SyncStatus.firstChoice] asks what to do; the old folder's file stays.
     */
    suspend fun useFolder(path: String) {
        val clean = path.split('/').filter { it.isNotBlank() }.joinToString("/")
        mutex.withLock {
            changingFolder = false
            prefs.edit()
                .putString(K_FOLDER, clean)
                .putBoolean(K_FIRST_DONE, false)
                .remove(K_ETAG).remove(K_STAMP).remove(K_PREFS_AT).remove(K_ERROR)
                .apply()
            _status.value = readStatus()
        }
        prepareFirst()
    }

    /** The picker was closed: right after connecting that means the usual "Poké Trader" folder. */
    fun cancelFolderChoice() {
        if (!prefs.contains(K_FOLDER)) scope.launch { useFolder(NextcloudClient.FOLDER) }
        else {
            changingFolder = false
            _status.value = readStatus()
        }
    }

    /** Looks at both sides before the first sync; runs it right away unless the user has to choose. */
    suspend fun prepareFirst() {
        val account = account() ?: return
        val remote = try {
            when (val r = client.get(account, null)) {
                is NextcloudClient.Remote.Found -> SyncFile.decode(r.bytes).data
                else -> null
            }
        } catch (e: IOException) {
            _status.value = _status.value.copy(lastError = "Couldn't reach Nextcloud: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        val (local, _) = store.snapshot()
        if (!local.isEmpty) beforeFirstSync?.invoke()
        // Only ask when this phone holds something Nextcloud doesn't (e.g. not when reconnecting the same phone).
        val now = System.currentTimeMillis()
        val phoneAddsSomething = remote != null && SyncMerge.merge(local, remote, now, FirstSync.MERGE) != SyncMerge.merge(remote, null, now)
        if (remote != null && !remote.isEmpty && !local.isEmpty && phoneAddsSomething) {
            _status.value = _status.value.copy(firstChoice = FirstChoice(SyncSummary.of(local), SyncSummary.of(remote)))
        } else {
            sync(FirstSync.MERGE)
        }
    }

    fun cancelFirstChoice() {
        _status.value = _status.value.copy(firstChoice = null)
    }

    /** The user's answer to [SyncStatus.firstChoice]. */
    fun chooseFirst(choice: FirstSync) {
        _status.value = _status.value.copy(firstChoice = null)
        scope.launch { sync(choice) }
    }

    /** Forgets the account (and removes its app password from Nextcloud if this app created it). Data on the phone stays. */
    fun disconnect() {
        val account = account()
        val revoke = prefs.getBoolean(K_REVOKE, false)
        prefs.edit().clear().putBoolean(K_AUTO, _status.value.autoSync).putBoolean(K_WIFI, _status.value.wifiOnly).apply()
        _status.value = readStatus()
        schedule()
        if (account != null && revoke) scope.launch { runCatching { client.revoke(account) } }
    }

    fun setAutoSync(on: Boolean) {
        prefs.edit().putBoolean(K_AUTO, on).apply()
        _status.value = _status.value.copy(autoSync = on)
        schedule()
    }

    fun setWifiOnly(on: Boolean) {
        prefs.edit().putBoolean(K_WIFI, on).apply()
        _status.value = _status.value.copy(wifiOnly = on)
        schedule()
    }

    // ---- When to sync ----------------------------------------------------------------------

    /** On opening the app. */
    fun onAppStart() {
        schedule()
        if (autoAllowed()) scope.launch { sync() }
    }

    private fun onDataChanged() {
        if (!autoAllowed() || mutex.isLocked) return
        debounce?.cancel()
        debounce = scope.launch {
            delay(DEBOUNCE_MS)
            if (hasLocalChanges()) sync()
        }
    }

    /** On leaving the app: changes not synced yet go out in the background, even if Android closes the app. */
    fun onAppBackground() {
        val s = _status.value
        if (!s.connected || !firstDone || !s.autoSync) return
        scope.launch {
            if (!hasLocalChanges()) return@launch
            val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_ONCE, ExistingWorkPolicy.REPLACE, request)
        }
    }

    private suspend fun hasLocalChanges(): Boolean =
        store.lastChange() != prefs.getLong(K_STAMP, -1) || store.prefsUpdatedAt() != prefs.getLong(K_PREFS_AT, -1)

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(if (_status.value.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    /** Sets up (or cancels) the hourly background sync to match the settings. */
    fun schedule() {
        val wm = WorkManager.getInstance(context)
        val s = _status.value
        if (!s.connected || !s.autoSync) {
            wm.cancelUniqueWork(WORK_PERIODIC)
            wm.cancelUniqueWork(WORK_ONCE)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).setConstraints(constraints()).build()
        wm.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** For the background worker. */
    suspend fun autoSync() {
        if (autoAllowed()) sync()
    }

    /** The user has read the warning about [SyncStatus.olderPhone]; Settings keeps a note until it's updated. */
    fun acknowledgeOlderPhone() {
        prefs.edit().putBoolean(K_OLDER_SEEN, true).apply()
        _status.value = _status.value.copy(olderPhone = _status.value.olderPhone?.copy(acknowledged = true))
    }

    /** Forgets [SyncStatus.olderPhone] (e.g. that phone isn't used any more). */
    fun forgetOlderPhone() {
        prefs.edit().remove(K_OLDER).remove(K_OLDER_AT).remove(K_OLDER_SEEN).apply()
        _status.value = _status.value.copy(olderPhone = null)
    }

    /** Notes a phone still on an older version in [file], or that the one noted before has been updated. */
    private fun checkWriter(file: SyncFile, etag: String?) {
        val older = SyncFile.olderWriter(file, etag, prefs.getString(K_ETAG, null), device())
        val noted = prefs.getString(K_OLDER, null)
        when {
            older != null && older != noted -> {
                prefs.edit().putString(K_OLDER, older).putLong(K_OLDER_AT, file.writtenAt).putBoolean(K_OLDER_SEEN, false).apply()
                _status.value = _status.value.copy(olderPhone = OlderPhone(older, file.writtenAt))
            }
            older == null && noted != null && file.format >= SyncFile.FORMAT && file.writtenBy == noted -> forgetOlderPhone()
        }
    }

    private fun device() = "${Build.MANUFACTURER} ${Build.MODEL}"

    // ---- Syncing ---------------------------------------------------------------------------

    fun syncNow() {
        scope.launch { sync() }
    }

    /**
     * Syncs everything now, even if nothing seems to have changed: after writes made with change
     * tracking paused (restoring a backup), which the usual "anything new?" check can't see.
     */
    fun syncAllNow() {
        prefs.edit().remove(K_STAMP).remove(K_ETAG).remove(K_PREFS_AT).apply()
        scope.launch { sync() }
    }

    /** Syncs once; errors end up in [SyncStatus.lastError]. Returns whether it succeeded. */
    suspend fun sync(first: FirstSync? = null): Boolean = mutex.withLock {
        val account = account() ?: return false
        if (first == null && !firstDone) return false
        _status.value = _status.value.copy(running = true)
        val error = try {
            runSync(account, first)
            null
        } catch (e: SyncException) {
            e.message
        } catch (e: IOException) {
            "Couldn't reach Nextcloud: ${e.message ?: e.javaClass.simpleName}"
        } catch (e: kotlinx.serialization.SerializationException) {
            "The file on Nextcloud couldn't be read."
        }
        val now = System.currentTimeMillis()
        prefs.edit().apply {
            if (error == null) putLong(K_LAST_SYNC, now).remove(K_ERROR) else putString(K_ERROR, error)
        }.apply()
        _status.value = _status.value.copy(
            running = false, lastError = error, ready = firstDone,
            lastSyncAt = if (error == null) now else _status.value.lastSyncAt,
        )
        error == null
    }

    private suspend fun runSync(account: NextcloudAccount, first: FirstSync?) {
        repeat(ATTEMPTS) {
            val (local, stamp) = store.snapshot()
            val unchanged = first == null && stamp == prefs.getLong(K_STAMP, -1) && local.prefs.updatedAt == prefs.getLong(K_PREFS_AT, -1)
            val remote = client.get(account, if (unchanged) prefs.getString(K_ETAG, null) else null)
            if (remote is NextcloudClient.Remote.NotModified) return
            val found = remote as? NextcloudClient.Remote.Found
            val remoteFile = found?.let { f -> SyncFile.decode(f.bytes).also { checkWriter(it, f.etag) } }
            val remoteData = remoteFile?.data
            val now = System.currentTimeMillis()
            val merged = SyncMerge.merge(local, remoteData, now, first)

            if (merged != local && !store.apply(local, merged, stamp)) return@repeat // edited meanwhile: start over
            var etag = found?.etag
            // A file from an older version is rewritten in this one's format even if nothing changed, so phones still on it stop and ask to be updated.
            if (merged != remoteData || (remoteFile != null && remoteFile.format < SyncFile.FORMAT)) {
                val file = SyncFile(writtenAt = now, writtenBy = device(), data = merged)
                when (val put = client.put(account, SyncFile.encode(file), found?.etag)) {
                    NextcloudClient.PutResult.Conflict -> return@repeat // another phone wrote meanwhile: start over
                    is NextcloudClient.PutResult.Ok -> etag = put.etag
                }
            }
            prefs.edit()
                .putString(K_ETAG, etag)
                .putLong(K_STAMP, store.lastChange())
                .putLong(K_PREFS_AT, merged.prefs.updatedAt)
                .putBoolean(K_FIRST_DONE, true)
                .apply()
            return
        }
        throw SyncException("Sync kept colliding with other changes; it will try again later.")
    }

    private companion object {
        const val K_SERVER = "server"
        const val K_USER = "user"
        const val K_USER_ID = "userId"
        const val K_PASSWORD = "password"
        const val K_REVOKE = "revokeOnDisconnect"
        const val K_FIRST_DONE = "firstDone"
        const val K_ETAG = "etag"
        const val K_STAMP = "syncedStamp"
        const val K_PREFS_AT = "syncedPrefsAt"
        const val K_LAST_SYNC = "lastSync"
        const val K_ERROR = "lastError"
        const val K_AUTO = "auto"
        const val K_WIFI = "wifiOnly"
        const val K_FOLDER = "folder"
        const val K_OLDER = "olderPhone"
        const val K_OLDER_AT = "olderPhoneAt"
        const val K_OLDER_SEEN = "olderPhoneSeen"
        const val WORK_PERIODIC = "nextcloud-sync"
        const val WORK_ONCE = "nextcloud-sync-once"
        const val DEBOUNCE_MS = 30_000L
        const val ATTEMPTS = 4
    }
}

/** Background sync; see [SyncManager.schedule] and [SyncManager.onAppBackground]. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.container.sync.autoSync()
        return Result.success()
    }
}
