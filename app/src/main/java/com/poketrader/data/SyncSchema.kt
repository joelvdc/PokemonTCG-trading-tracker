package com.poketrader.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.Serializable

/** A synced item that was deleted, by its sync id. Since 1.20. */
@Serializable
@Entity(tableName = "sync_deletions")
data class SyncDeletion(@PrimaryKey val uid: String, val deletedAt: Long)

/**
 * One row: while [applying] is 1, the change-tracking triggers stand still (sync is writing);
 * [changes] counts the edits made in the app, so sync can tell whether there's anything new.
 */
@Entity(tableName = "sync_control")
data class SyncControl(@PrimaryKey val id: Int = 1, val applying: Int = 0, @ColumnInfo(defaultValue = "0") val changes: Long = 0)

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_deletions")
    suspend fun deletions(): List<SyncDeletion>

    @Query("DELETE FROM sync_deletions")
    suspend fun clearDeletions()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDeletions(rows: List<SyncDeletion>)

    @Query("UPDATE sync_control SET applying = :on WHERE id = 1")
    suspend fun setApplying(on: Int)

    @Query("SELECT * FROM binders")
    suspend fun binders(): List<Binder>

    @Query("SELECT * FROM collection")
    suspend fun collection(): List<CollectionItem>

    @Query("SELECT * FROM scans")
    suspend fun scans(): List<ScannedCard>

    @Query("SELECT * FROM trade_items")
    suspend fun tradeItems(): List<TradeItem>

    @Query("SELECT * FROM trades")
    suspend fun trades(): List<Trade>

    @Query("SELECT * FROM wishlist")
    suspend fun wishlist(): List<WishlistItem>

    /** Edits made in the app so far (see [SyncControl.changes]). */
    @Query("SELECT changes FROM sync_control WHERE id = 1")
    suspend fun lastChange(): Long?
}

/**
 * Change tracking for sync, done by SQLite triggers so every edit in the app is covered: inserting or
 * updating a binder, collection stack, trade, scan or wishlist card stamps its updatedAt (and gives it
 * a sync id), deleting one records a [SyncDeletion]. A trade's cards count as part of it, so changing
 * them stamps the trade. While sync itself writes, [SyncControl.applying] is 1 and the triggers stand
 * still. Since 1.20 (the same as MTG Trader's).
 */
object SyncSchema {
    private const val ACTIVE = "(SELECT applying FROM sync_control WHERE id = 1) = 0"
    const val NOW = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
    private const val NEW_UID = "lower(hex(randomblob(16)))"
    /** Strictly later than before, so the triggers below can tell their own updates apart (SQLite on Android runs triggers recursively). */
    private const val BUMP = "updatedAt = MAX($NOW, updatedAt + 1)"
    private const val COUNT = "UPDATE sync_control SET changes = changes + 1 WHERE id = 1;"

    /** Tables whose rows sync on their own, identified by their uid column. */
    val UID_TABLES = listOf("collection", "binders", "trades", "scans", "wishlist")

    fun triggers(): List<String> = buildList {
        for (t in UID_TABLES) {
            // A copy of an existing row (e.g. part of a stack moved to another binder) gets its own id.
            add(
                "CREATE TRIGGER IF NOT EXISTS sync_${t}_ins AFTER INSERT ON `$t` WHEN $ACTIVE BEGIN " +
                    "UPDATE `$t` SET uid = CASE WHEN uid IS NULL OR EXISTS (SELECT 1 FROM `$t` o WHERE o.uid = NEW.uid AND o.rowid <> NEW.rowid) " +
                    "THEN $NEW_UID ELSE uid END, $BUMP WHERE rowid = NEW.rowid; " +
                    "DELETE FROM sync_deletions WHERE uid = NEW.uid; $COUNT END"
            )
            add(
                "CREATE TRIGGER IF NOT EXISTS sync_${t}_upd AFTER UPDATE ON `$t` WHEN $ACTIVE AND NEW.updatedAt = OLD.updatedAt BEGIN " +
                    "UPDATE `$t` SET uid = COALESCE(uid, $NEW_UID), $BUMP WHERE rowid = NEW.rowid; $COUNT END"
            )
            add(
                "CREATE TRIGGER IF NOT EXISTS sync_${t}_del AFTER DELETE ON `$t` WHEN $ACTIVE AND OLD.uid IS NOT NULL BEGIN " +
                    "INSERT OR REPLACE INTO sync_deletions(uid, deletedAt) VALUES (OLD.uid, $NOW); $COUNT END"
            )
        }
        // A trade's cards are synced with it: changing them is a change to the trade.
        for ((event, row) in listOf("INSERT" to "NEW", "UPDATE" to "NEW", "DELETE" to "OLD")) {
            add(
                "CREATE TRIGGER IF NOT EXISTS sync_trade_items_${event.lowercase()} AFTER $event ON trade_items WHEN $ACTIVE BEGIN " +
                    "UPDATE trades SET $BUMP WHERE id = $row.tradeId; $COUNT END"
            )
        }
    }

    /** Run on every open: the triggers are (re)created as this version defines them, the control row exists, and tracking is on. */
    fun install(db: SupportSQLiteDatabase) {
        db.query("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'sync!_%' ESCAPE '!'").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }.forEach { db.execSQL("DROP TRIGGER IF EXISTS `$it`") }
        triggers().forEach(db::execSQL)
        db.execSQL("INSERT OR IGNORE INTO sync_control(id, applying) VALUES (1, 0)")
        db.execSQL("UPDATE sync_control SET applying = 0 WHERE id = 1")
    }
}
