package com.poketrader.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A restore point: everything that syncs between phones (the collection, binders, trades, scans,
 * wishlist and synced settings) at one moment. Since 1.20 (as MTG Trader's).
 */
@Serializable
data class BackupFile(
    val format: Int = FORMAT,
    val app: String = SyncFile.APP,
    val createdAt: Long = 0,
    val reason: String = "",
    val device: String = "",
    val manual: Boolean = false,
    val data: SyncData = SyncData(),
) {
    companion object {
        /** 1 since 1.20, like [SyncFile.FORMAT]. */
        const val FORMAT = 1
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(file: BackupFile): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(json.encodeToString(serializer(), file).toByteArray(Charsets.UTF_8)) }
            return out.toByteArray()
        }

        /** Reads a backup, or the Nextcloud sync file (which holds the same data). */
        fun decode(bytes: ByteArray): BackupFile {
            val text = try {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.toString(Charsets.UTF_8)
            } catch (e: IOException) {
                bytes.toString(Charsets.UTF_8)
            }
            val file = try {
                json.decodeFromString(serializer(), text)
            } catch (e: Exception) {
                throw SyncException("That isn't a Poké Trader backup.")
            }
            if (file.format > FORMAT) throw SyncException("That backup was made by a newer version of the app. Update the app first.")
            if (file.app != SyncFile.APP) throw SyncException("That isn't a Poké Trader backup.")
            return file
        }
    }
}

/** Why a backup was made; the label goes into the file name. */
enum class BackupReason(val label: String) {
    MANUAL("Made by hand"),
    DAILY("Daily backup"),
    FIRST_NEXTCLOUD("Before the first Nextcloud sync"),
    CSV_IMPORT("Before a CSV import"),
    BATCH_DELETE("Before deleting selected cards"),
    RESTORE("Before restoring a backup"),
}

enum class BackupPlace(val label: String) { NEXTCLOUD("Nextcloud"), PHONE("This phone"), FOLDER("Chosen folder") }

/** A backup file somewhere: [ref] is its name on Nextcloud, its path on the phone or its document address in the chosen folder. */
data class BackupInfo(
    val place: BackupPlace,
    val name: String,
    val ref: String,
    val createdAt: Long,
    val reason: String,
    val manual: Boolean,
    val size: Long,
)

/** What a backup holds compared with the app now, for the restore preview. */
data class BackupDiff(
    val cardsNow: Int,
    val cardsThen: Int,
    val bindersNow: Int,
    val bindersThen: Int,
    val tradesNow: Int,
    val tradesThen: Int,
    val wishlistNow: Int,
    val wishlistThen: Int,
    /** Card names with how many more (+) or fewer (-) copies the backup has, biggest changes first. */
    val cardChanges: List<Pair<String, Int>>,
) {
    val same get() = cardChanges.isEmpty() && bindersNow == bindersThen && tradesNow == tradesThen && wishlistNow == wishlistThen

    companion object {
        fun of(now: SyncData, then: SyncData): BackupDiff {
            fun counts(d: SyncData) = d.collection.groupBy { it.item.card.name }.mapValues { (_, s) -> s.sumOf { it.item.quantity } }
            val a = counts(now)
            val b = counts(then)
            val changes = (a.keys + b.keys).mapNotNull { n -> ((b[n] ?: 0) - (a[n] ?: 0)).takeIf { it != 0 }?.let { n to it } }
                .sortedWith(compareByDescending<Pair<String, Int>> { kotlin.math.abs(it.second) }.thenBy { it.first })
            return BackupDiff(
                now.collection.sumOf { it.item.quantity }, then.collection.sumOf { it.item.quantity },
                now.binders.size, then.binders.size,
                now.trades.size, then.trades.size, now.wishlist.size, then.wishlist.size, changes,
            )
        }
    }
}

/** Which automatic backups to keep: the newest [KEEP_LATEST], plus the newest of each of the last [KEEP_WEEKS] weeks. Ones made by hand stay. */
object BackupRetention {
    const val KEEP_LATEST = 10
    const val KEEP_WEEKS = 8
    private const val WEEK = 7L * 24 * 60 * 60 * 1000

    fun toDelete(backups: List<BackupInfo>, now: Long): List<BackupInfo> {
        val auto = backups.filterNot { it.manual }.sortedByDescending { it.createdAt }
        val keep = auto.take(KEEP_LATEST).toMutableSet()
        for (w in 0 until KEEP_WEEKS) {
            val to = now - w * WEEK
            val from = to - WEEK
            auto.firstOrNull { it.createdAt in from until to }?.let { keep += it }
        }
        return auto.filterNot { it in keep }
    }
}

/**
 * Restoring a backup: the app's data becomes the backup's, as changes made now, so they win over
 * what other phones hold and spread to them through Nextcloud. What's here but not in the backup is
 * deleted (with tombstones, so other phones delete it too); what differs is stamped with [now].
 */
object BackupRestore {
    fun target(current: SyncData, backup: SyncData, now: Long): SyncData {
        val gone = mutableListOf<SyncDeletion>()
        fun <T> pick(cur: List<T>, back: List<T>, key: (T) -> String?, stamp: (T) -> T): List<T> {
            val byKey = cur.associateBy(key)
            val keep = back.mapNotNull(key).toSet()
            for (x in cur) key(x)?.takeIf { it !in keep }?.let { gone += SyncDeletion(it, now) }
            return back.filter { key(it) != null }.map { b -> if (byKey[key(b)] == b) b else stamp(b) }
        }
        val binders = pick(current.binders, backup.binders, { it.uid }) { it.copy(updatedAt = now) }
        val collection = pick(current.collection, backup.collection, { it.item.uid }) { it.copy(item = it.item.copy(updatedAt = now)) }
        val trades = pick(current.trades, backup.trades, { it.trade.uid }) { it.copy(trade = it.trade.copy(updatedAt = now)) }
        val scans = pick(current.scans, backup.scans, { it.uid }) { it.copy(updatedAt = now) }
        val wishlist = pick(current.wishlist, backup.wishlist, { it.uid }) { it.copy(updatedAt = now) }
        val alive = buildSet {
            binders.forEach { add(it.uid) }; collection.forEach { add(it.item.uid) }; trades.forEach { add(it.trade.uid) }
            scans.forEach { add(it.uid) }; wishlist.forEach { add(it.uid) }
        }
        val deletions = (current.deletions.filter { it.uid !in alive } + gone).groupBy { it.uid }.map { (_, d) -> d.maxBy { it.deletedAt } }
        val prefs = if (backup.prefs.values == current.prefs.values) current.prefs else backup.prefs.copy(updatedAt = now)
        return SyncData(binders, collection, trades, scans, wishlist, deletions, prefs).sorted()
    }
}

/**
 * Makes, lists, prunes and restores backups: on Nextcloud (a "Backups" folder in the sync folder)
 * when this phone syncs there, otherwise on the phone (in the app's storage, or in a folder the
 * owner picked so they survive uninstalling the app).
 */
class Backups(
    private val context: Context,
    private val store: SyncStore,
    private val sync: SyncManager,
    private val client: NextcloudClient,
) {
    private val prefs = context.getSharedPreferences("backups", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val localDir get() = File(context.filesDir, "backups").apply { mkdirs() }

    private val _working = MutableStateFlow<String?>(null)
    /** What's going on right now ("Making a backup…"), or null. */
    val working: StateFlow<String?> = _working

    private val _lastError = MutableStateFlow<String?>(prefs.getString(K_ERROR, null))
    val lastError: StateFlow<String?> = _lastError

    private val _changed = MutableStateFlow(0)
    /** Bumped whenever the list of backups changes. */
    val changed: StateFlow<Int> = _changed

    /** Also keep backups on the phone when they go to Nextcloud. */
    var alsoOnPhone: Boolean
        get() = prefs.getBoolean(K_ALSO_PHONE, false)
        set(v) = prefs.edit().putBoolean(K_ALSO_PHONE, v).apply()

    /** The folder the user picked for backups on the phone (a document tree), or null for the app's own storage. */
    var folder: Uri?
        get() = prefs.getString(K_FOLDER, null)?.let(Uri::parse)
        set(v) = prefs.edit().putString(K_FOLDER, v?.toString()).apply()

    val lastBackupAt: Long get() = prefs.getLong(K_LAST_AT, 0)

    // ---- Making backups --------------------------------------------------------------------

    /**
     * Makes a backup of the app's data now. Automatic ones are pruned afterwards. Returns where it
     * went, or throws when it couldn't be saved anywhere.
     */
    suspend fun create(reason: BackupReason): List<BackupPlace> = mutex.withLock {
        _working.value = "Making a backup…"
        try {
            val (data, stamp) = store.snapshot()
            val now = System.currentTimeMillis()
            val file = BackupFile(createdAt = now, reason = reason.label, device = device(), manual = reason == BackupReason.MANUAL, data = data)
            val bytes = BackupFile.encode(file)
            val name = fileName(now, reason.label, file.manual)
            val places = mutableListOf<BackupPlace>()
            var error: String? = null
            val account = sync.readyAccount()
            if (account != null) {
                try {
                    client.putIn(account, "$DIR/$name", bytes, null)
                    places += BackupPlace.NEXTCLOUD
                } catch (e: Exception) {
                    error = "Couldn't save the backup on Nextcloud (${e.message ?: e.javaClass.simpleName}), so it was saved on this phone."
                }
            }
            if (account == null || alsoOnPhone || BackupPlace.NEXTCLOUD !in places) {
                places += savePhone(name, bytes)
            }
            prefs.edit().putLong(K_LAST_AT, now).putLong(K_STAMP, stamp).putString(K_ERROR, error).apply()
            _lastError.value = error
            if (!file.manual) runCatching { prune(now) }
            _changed.value++
            places
        } catch (e: Exception) {
            val msg = "The backup failed: ${e.message ?: e.javaClass.simpleName}"
            prefs.edit().putString(K_ERROR, msg).apply()
            _lastError.value = msg
            throw e
        } finally {
            _working.value = null
        }
    }

    /** A daily backup, when something changed since the last one and none was made for about a day. */
    suspend fun dailyIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastBackupAt < DAILY_MS) return
        val stamp = store.lastChange()
        if (stamp == prefs.getLong(K_STAMP, -1) && lastBackupAt > 0) return
        if (stamp == 0L && store.snapshot().first.isEmpty) return
        runCatching { create(BackupReason.DAILY) }
    }

    /** An automatic backup before something that changes a lot; never stops that something from going ahead. */
    suspend fun before(reason: BackupReason) {
        runCatching { create(reason) }
    }

    /** The current data as a backup file, for "Save a backup file…". */
    suspend fun exportBytes(): ByteArray {
        val (data, _) = store.snapshot()
        return BackupFile.encode(BackupFile(createdAt = System.currentTimeMillis(), reason = BackupReason.MANUAL.label, device = device(), manual = true, data = data))
    }

    fun suggestedFileName(): String = "Poke Trader backup " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date()) + ".json.gz"

    private suspend fun savePhone(name: String, bytes: ByteArray): BackupPlace = withContext(Dispatchers.IO) {
        val tree = folder
        if (tree != null) {
            try {
                val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(context.contentResolver, dir, "application/gzip", name)
                    ?: throw IOException("the folder didn't accept the file")
                context.contentResolver.openOutputStream(doc)!!.use { it.write(bytes) }
                return@withContext BackupPlace.FOLDER
            } catch (e: Exception) {
                // The folder is gone or no longer allowed: fall back to the app's storage.
            }
        }
        File(localDir, name).writeBytes(bytes)
        BackupPlace.PHONE
    }

    // ---- Listing and pruning ---------------------------------------------------------------

    /** All backups, newest first, and a note if Nextcloud couldn't be read. */
    suspend fun list(): Pair<List<BackupInfo>, String?> {
        val out = mutableListOf<BackupInfo>()
        var note: String? = null
        sync.readyAccount()?.let { account ->
            try {
                client.filesIn(account, DIR).forEach { f -> parse(f.name)?.let { out += it.copy(place = BackupPlace.NEXTCLOUD, ref = f.name, size = f.size) } }
            } catch (e: Exception) {
                note = "Couldn't read the backups on Nextcloud: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        withContext(Dispatchers.IO) {
            localDir.listFiles()?.forEach { f -> parse(f.name)?.let { out += it.copy(place = BackupPlace.PHONE, ref = f.absolutePath, size = f.length()) } }
            folder?.let { tree -> runCatching { out += folderFiles(tree) } }
        }
        return out.sortedByDescending { it.createdAt } to note
    }

    private fun folderFiles(tree: Uri): List<BackupInfo> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE)
        val out = mutableListOf<BackupInfo>()
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val info = parse(c.getString(1) ?: continue) ?: continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                out += info.copy(place = BackupPlace.FOLDER, ref = uri.toString(), size = c.getLong(2))
            }
        }
        return out
    }

    private suspend fun prune(now: Long) {
        val (all, _) = list()
        for ((_, group) in all.groupBy { it.place }) {
            for (b in BackupRetention.toDelete(group, now)) runCatching { deleteFile(b) }
        }
    }

    suspend fun delete(b: BackupInfo) {
        deleteFile(b)
        _changed.value++
    }

    private suspend fun deleteFile(b: BackupInfo) {
        when (b.place) {
            BackupPlace.NEXTCLOUD -> client.deleteIn(sync.readyAccount() ?: throw SyncException("Nextcloud isn't connected."), "$DIR/${b.ref}")
            BackupPlace.PHONE -> withContext(Dispatchers.IO) { File(b.ref).delete() }
            BackupPlace.FOLDER -> withContext(Dispatchers.IO) { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(b.ref)) }
        }
    }

    suspend fun read(b: BackupInfo): BackupFile {
        val bytes = when (b.place) {
            BackupPlace.NEXTCLOUD -> when (val r = client.getIn(sync.readyAccount() ?: throw SyncException("Nextcloud isn't connected."), "$DIR/${b.ref}")) {
                is NextcloudClient.Remote.Found -> r.bytes
                else -> throw SyncException("That backup isn't on Nextcloud any more.")
            }
            BackupPlace.PHONE -> withContext(Dispatchers.IO) { File(b.ref).readBytes() }
            BackupPlace.FOLDER -> withContext(Dispatchers.IO) { context.contentResolver.openInputStream(Uri.parse(b.ref))!!.use { it.readBytes() } }
        }
        return BackupFile.decode(bytes)
    }

    suspend fun readUri(uri: Uri): BackupFile =
        BackupFile.decode(withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } })

    // ---- Restoring -------------------------------------------------------------------------

    suspend fun diff(backup: BackupFile): BackupDiff = BackupDiff.of(store.snapshot().first, backup.data)

    /**
     * Replaces the app's data with [backup]'s, after a backup of the current state. The other phone
     * gets it through the next Nextcloud sync.
     */
    suspend fun restore(backup: BackupFile) {
        before(BackupReason.RESTORE)
        _working.value = "Restoring…"
        try {
            repeat(4) {
                val (current, stamp) = store.snapshot()
                val target = BackupRestore.target(current, backup.data, System.currentTimeMillis())
                if (store.apply(current, target, stamp)) {
                    sync.syncAllNow()
                    return
                }
            }
            throw SyncException("The data kept changing while restoring; try again.")
        } finally {
            _working.value = null
        }
    }

    private fun device() = "${Build.MANUFACTURER} ${Build.MODEL}"

    companion object {
        const val DIR = "Backups"
        private const val K_ALSO_PHONE = "alsoOnPhone"
        private const val K_FOLDER = "folder"
        private const val K_LAST_AT = "lastAt"
        private const val K_STAMP = "stamp"
        private const val K_ERROR = "lastError"
        private const val DAILY_MS = 20L * 60 * 60 * 1000
        private val stampFormat get() = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US)
        private val nameRegex = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}\.\d{2}\.\d{2}) - (.*?)( \(kept\))?\.json\.gz$""")

        /** "2026-10-05 14.30.15 - Before a CSV import.json.gz"; ones made by hand end in "(kept)". */
        fun fileName(at: Long, reason: String, manual: Boolean): String =
            stampFormat.format(Date(at)) + " - " + reason.replace(Regex("""[\\/:*?"<>|]"""), " ") + (if (manual) " (kept)" else "") + ".json.gz"

        fun parse(name: String): BackupInfo? {
            val m = nameRegex.matchEntire(name) ?: return null
            val at = runCatching { stampFormat.parse(m.groupValues[1])?.time }.getOrNull() ?: return null
            return BackupInfo(BackupPlace.PHONE, name, name, at, m.groupValues[2], m.groupValues[3].isNotEmpty(), 0)
        }
    }
}
