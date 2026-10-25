package com.poketrader.data

import androidx.room.withTransaction

/** Reads this phone's data as [SyncData] and writes merged data back, with change tracking paused. Since 1.20. */
class SyncStore(private val db: AppDatabase, private val settings: Settings) {
    private val dao = db.syncDao()

    /** This phone's data, and its [lastChange] at that moment. */
    suspend fun snapshot(): Pair<SyncData, Long> = db.withTransaction {
        val binders = dao.binders()
        val binderUid = binders.associate { it.id to it.uid }
        val items = dao.tradeItems().groupBy { it.tradeId }
        SyncData(
            binders = binders.map { it.copy(id = 0) },
            collection = dao.collection().map { SyncStack(it.copy(id = 0, binderId = 0), binderUid[it.binderId]) },
            trades = dao.trades().map { t ->
                SyncTrade(
                    trade = t.copy(id = 0, binderId = 0),
                    binder = binderUid[t.binderId],
                    items = items[t.id].orEmpty().map { SyncTradeItem(it.copy(id = 0, tradeId = 0, appliedBinderId = 0), binderUid[it.appliedBinderId]) },
                )
            },
            scans = dao.scans().map { it.copy(id = 0) },
            wishlist = dao.wishlist().map { it.copy(id = 0) },
            deletions = dao.deletions(),
            prefs = settings.syncedPrefs(),
        ).sorted() to (dao.lastChange() ?: 0L)
    }

    /** The number of edits made in the app so far, to tell whether anything changed since the last sync. */
    suspend fun lastChange(): Long = dao.lastChange() ?: 0L

    fun prefsUpdatedAt(): Long = settings.syncedPrefs().updatedAt

    /**
     * Makes the database hold exactly [merged] ([current] is what [snapshot] returned), touching only rows
     * that differ, and keeping each row's local id so open screens stay put. Returns false (writing
     * nothing) if something was edited since the snapshot ([lastChange] moved on).
     */
    suspend fun apply(current: SyncData, merged: SyncData, lastChange: Long): Boolean {
        val done = db.withTransaction {
            if ((dao.lastChange() ?: 0L) != lastChange) return@withTransaction false
            dao.setApplying(1)
            try {
                applyRows(current, merged)
            } finally {
                dao.setApplying(0)
            }
            true
        }
        if (done && merged.prefs != current.prefs) settings.applySyncedPrefs(merged.prefs)
        return done
    }

    private suspend fun applyRows(current: SyncData, merged: SyncData) {
        val w = db.openHelper.writableDatabase
        fun exec(sql: String, vararg args: Any?) = w.execSQL(sql, args)

        // Binders first: the other rows refer to them.
        val localBinders = dao.binders()
        val binderByUid = localBinders.filter { it.uid != null }.associateBy { it.uid!! }
        val keepBinders = merged.binders.mapNotNull { it.uid }.toSet()
        for (b in localBinders) if (b.uid !in keepBinders) exec("DELETE FROM binders WHERE id = ?", b.id)
        val binderId = HashMap<String, Long>()
        for (b in merged.binders) {
            val mine = binderByUid[b.uid]
            if (mine == null) binderId[b.uid!!] = db.binderDao().insert(b.copy(id = 0))
            else {
                binderId[b.uid!!] = mine.id
                if (mine.copy(id = 0) != b) exec("UPDATE binders SET name = ?, createdAt = ?, updatedAt = ? WHERE id = ?", b.name, b.createdAt, b.updatedAt, mine.id)
            }
        }
        fun bid(uid: String?) = uid?.let { binderId[it] } ?: Binder.UNSORTED

        // Collection: drop what's gone or changed first (so no two rows briefly share a card and binder), then (re)insert.
        if (current.collection != merged.collection) {
            val local = dao.collection()
            val localByUid = local.filter { it.uid != null }.associateBy { it.uid!! }
            val wanted = merged.collection.associate { s -> s.item.uid!! to s.item.copy(binderId = bid(s.binder)) }
            val changed = ArrayList<CollectionItem>()
            for (row in local) {
                val want = wanted[row.uid]
                if (want == null || want.copy(id = row.id) != row) exec("DELETE FROM collection WHERE id = ?", row.id)
            }
            for ((uid, want) in wanted) {
                val mine = localByUid[uid]
                if (mine == null) changed += want.copy(id = 0)
                else if (want.copy(id = mine.id) != mine) changed += want.copy(id = mine.id)
            }
            for (row in changed) db.collectionDao().insert(row)
        }

        if (current.scans != merged.scans) {
            val local = dao.scans()
            val localByUid = local.filter { it.uid != null }.associateBy { it.uid!! }
            val wanted = merged.scans.associateBy { it.uid!! }
            for (row in local) {
                val want = wanted[row.uid]
                if (want == null || want.copy(id = row.id) != row) exec("DELETE FROM scans WHERE id = ?", row.id)
            }
            for ((uid, want) in wanted) {
                val mine = localByUid[uid]
                if (mine == null) db.scanDao().insert(want.copy(id = 0))
                else if (want.copy(id = mine.id) != mine) db.scanDao().insert(want.copy(id = mine.id))
            }
        }

        if (current.wishlist != merged.wishlist) {
            val local = dao.wishlist()
            val localByUid = local.filter { it.uid != null }.associateBy { it.uid!! }
            val wanted = merged.wishlist.associateBy { it.uid!! }
            for (row in local) {
                val want = wanted[row.uid]
                if (want == null || want.copy(id = row.id) != row) exec("DELETE FROM wishlist WHERE id = ?", row.id)
            }
            for ((uid, want) in wanted) {
                val mine = localByUid[uid]
                if (mine == null) db.wishlistDao().insert(want.copy(id = 0))
                else if (want.copy(id = mine.id) != mine) db.wishlistDao().insert(want.copy(id = mine.id))
            }
        }

        // Trades come with their cards: a changed one is replaced as a whole.
        val curTrades = current.trades.associateBy { it.trade.uid }
        val localTrades = dao.trades().filter { it.uid != null }.associateBy { it.uid!! }
        val keepTrades = merged.trades.mapNotNull { it.trade.uid }.toSet()
        for ((uid, t) in localTrades) if (uid !in keepTrades) exec("DELETE FROM trades WHERE id = ?", t.id)
        for (t in merged.trades) {
            val uid = t.trade.uid!!
            val mine = localTrades[uid]
            if (mine != null && curTrades[uid] == t) continue
            if (mine != null) exec("DELETE FROM trades WHERE id = ?", mine.id)
            val id = db.tradeDao().insert(t.trade.copy(id = mine?.id ?: 0, binderId = bid(t.binder)))
            for (i in t.items) db.tradeDao().insertItem(i.item.copy(id = 0, tradeId = id, appliedBinderId = bid(i.appliedBinder)))
        }

        if (current.deletions != merged.deletions) {
            dao.clearDeletions()
            dao.putDeletions(merged.deletions)
        }
    }
}
