package com.poketrader.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface PriceDao {
    @Query("DELETE FROM prices")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<PriceEntity>)

    @Query("SELECT * FROM prices WHERE idProduct = :id")
    suspend fun get(id: Int): PriceEntity?

    @Query("SELECT * FROM prices WHERE idProduct IN (:ids)")
    suspend fun getMany(ids: List<Int>): List<PriceEntity>

    @Query("SELECT COUNT(*) FROM prices")
    fun count(): Flow<Int>

    @Query("SELECT COUNT(*) FROM prices")
    suspend fun countNow(): Int
}

@Dao
interface CollectionDao {
    @Query(
        """SELECT c.*, p.idProduct AS pr_idProduct, p.avg AS pr_avg, p.low AS pr_low, p.trend AS pr_trend,
           p.avg1 AS pr_avg1, p.avg7 AS pr_avg7, p.avg30 AS pr_avg30, p.avgHolo AS pr_avgHolo,
           p.lowHolo AS pr_lowHolo, p.trendHolo AS pr_trendHolo, p.avg1Holo AS pr_avg1Holo,
           p.avg7Holo AS pr_avg7Holo, p.avg30Holo AS pr_avg30Holo
           FROM collection c LEFT JOIN prices p ON p.idProduct = c.cardmarketId
           ORDER BY c.name COLLATE NOCASE"""
    )
    fun observeAll(): Flow<List<CollectionRow>>

    @Query(
        """SELECT c.*, p.idProduct AS pr_idProduct, p.avg AS pr_avg, p.low AS pr_low, p.trend AS pr_trend,
           p.avg1 AS pr_avg1, p.avg7 AS pr_avg7, p.avg30 AS pr_avg30, p.avgHolo AS pr_avgHolo,
           p.lowHolo AS pr_lowHolo, p.trendHolo AS pr_trendHolo, p.avg1Holo AS pr_avg1Holo,
           p.avg7Holo AS pr_avg7Holo, p.avg30Holo AS pr_avg30Holo
           FROM collection c LEFT JOIN prices p ON p.idProduct = c.cardmarketId"""
    )
    suspend fun allWithPrices(): List<CollectionRow>

    /** Every stack of cards with this name (all sets, versions and binders), for the card page. Since 1.11. */
    @Query(
        """SELECT c.*, p.idProduct AS pr_idProduct, p.avg AS pr_avg, p.low AS pr_low, p.trend AS pr_trend,
           p.avg1 AS pr_avg1, p.avg7 AS pr_avg7, p.avg30 AS pr_avg30, p.avgHolo AS pr_avgHolo,
           p.lowHolo AS pr_lowHolo, p.trendHolo AS pr_trendHolo, p.avg1Holo AS pr_avg1Holo,
           p.avg7Holo AS pr_avg7Holo, p.avg30Holo AS pr_avg30Holo
           FROM collection c LEFT JOIN prices p ON p.idProduct = c.cardmarketId
           WHERE c.name = :name COLLATE NOCASE
           ORDER BY c.setName, c.localId"""
    )
    fun observeByName(name: String): kotlinx.coroutines.flow.Flow<List<CollectionRow>>

    @Query("SELECT cardId, SUM(quantity) AS qty FROM collection GROUP BY cardId")
    fun observeOwned(): Flow<List<OwnedCount>>

    @Query(
        """SELECT * FROM collection WHERE cardId = :cardId AND dataLang = :dataLang AND variantId = :variantId
           AND condition = :cond AND language = :lang AND binderId = :binderId LIMIT 1"""
    )
    suspend fun find(cardId: String, dataLang: String, variantId: String, cond: String, lang: String, binderId: Long): CollectionItem?

    /** Every stack of this card and variant, in any binder. */
    @Query("SELECT * FROM collection WHERE cardId = :cardId AND dataLang = :dataLang AND variantId = :variantId ORDER BY quantity DESC")
    suspend fun findAny(cardId: String, dataLang: String, variantId: String): List<CollectionItem>

    @Query("SELECT * FROM collection WHERE binderId = :binderId")
    suspend fun inBinder(binderId: Long): List<CollectionItem>

    @Query("DELETE FROM collection WHERE binderId = :binderId")
    suspend fun deleteBinderCards(binderId: Long)

    @Query("SELECT * FROM collection WHERE id = :id")
    suspend fun byId(id: Long): CollectionItem?

    @Query("SELECT * FROM collection ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<CollectionItem>

    @Insert
    suspend fun insert(item: CollectionItem): Long

    @Update
    suspend fun update(item: CollectionItem)

    @Query("DELETE FROM collection WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface TradeDao {
    @Transaction
    @Query("SELECT * FROM trades ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TradeWithItems>>

    @Transaction
    @Query("SELECT * FROM trades WHERE id = :id")
    fun observe(id: Long): Flow<TradeWithItems?>

    @Transaction
    @Query("SELECT * FROM trades WHERE id = :id")
    suspend fun get(id: Long): TradeWithItems?

    @Transaction
    @Query("SELECT * FROM trades ORDER BY createdAt DESC")
    suspend fun all(): List<TradeWithItems>

    @Insert
    suspend fun insert(trade: Trade): Long

    @Update
    suspend fun update(trade: Trade)

    @Query("UPDATE trades SET partner = :partner WHERE id = :id")
    suspend fun setPartner(id: Long, partner: String)

    @Query("UPDATE trades SET notes = :notes WHERE id = :id")
    suspend fun setNotes(id: Long, notes: String)

    @Query("DELETE FROM trades WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """DELETE FROM trades WHERE applied = 0 AND partner = '' AND notes = ''
           AND id NOT IN (SELECT DISTINCT tradeId FROM trade_items)"""
    )
    suspend fun deleteEmptyDrafts()

    @Insert
    suspend fun insertItem(item: TradeItem): Long

    @Update
    suspend fun updateItem(item: TradeItem)

    @Query("DELETE FROM trade_items WHERE id = :id")
    suspend fun deleteItem(id: Long)

    @Query("SELECT * FROM trade_items WHERE id = :id")
    suspend fun item(id: Long): TradeItem?

    @Query(
        """SELECT * FROM trade_items WHERE tradeId = :tradeId AND side = :side AND cardId = :cardId
           AND dataLang = :dataLang AND variantId = :variantId AND language = :lang AND customPrice IS NULL LIMIT 1"""
    )
    suspend fun findSame(tradeId: Long, side: String, cardId: String, dataLang: String, variantId: String, lang: String): TradeItem?

    /** Trades not yet applied to the collection, newest first. */
    @Query("SELECT * FROM trades WHERE applied = 0 ORDER BY createdAt DESC")
    fun observeOpen(): Flow<List<Trade>>
}

@Dao
interface BinderDao {
    @Query("SELECT * FROM binders ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Binder>>

    @Query("SELECT * FROM binders ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<Binder>

    @Query("SELECT * FROM binders WHERE id = :id")
    suspend fun get(id: Long): Binder?

    @Query("SELECT * FROM binders WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): Binder?

    @Insert
    suspend fun insert(binder: Binder): Long

    @Query("UPDATE binders SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM binders WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ScanDao {
    @Query(
        """SELECT s.*, p.idProduct AS pr_idProduct, p.avg AS pr_avg, p.low AS pr_low, p.trend AS pr_trend,
           p.avg1 AS pr_avg1, p.avg7 AS pr_avg7, p.avg30 AS pr_avg30, p.avgHolo AS pr_avgHolo,
           p.lowHolo AS pr_lowHolo, p.trendHolo AS pr_trendHolo, p.avg1Holo AS pr_avg1Holo,
           p.avg7Holo AS pr_avg7Holo, p.avg30Holo AS pr_avg30Holo
           FROM scans s LEFT JOIN prices p ON p.idProduct = s.cardmarketId
           ORDER BY s.scannedAt DESC"""
    )
    fun observeAll(): Flow<List<ScanRow>>

    @Query("SELECT COALESCE(SUM(quantity), 0) FROM scans")
    fun observeCount(): Flow<Int>

    @Query(
        """SELECT * FROM scans WHERE cardId = :cardId AND dataLang = :dataLang AND variantId = :variantId
           AND condition = :cond AND language = :lang LIMIT 1"""
    )
    suspend fun find(cardId: String, dataLang: String, variantId: String, cond: String, lang: String): ScannedCard?

    @Query("SELECT * FROM scans WHERE id = :id")
    suspend fun byId(id: Long): ScannedCard?

    @Query("SELECT * FROM scans")
    suspend fun all(): List<ScannedCard>

    @Query("SELECT * FROM scans WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<ScannedCard>

    @Insert
    suspend fun insert(item: ScannedCard): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restore(items: List<ScannedCard>)

    @Update
    suspend fun update(item: ScannedCard)

    @Query("DELETE FROM scans WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM scans WHERE id IN (:ids)")
    suspend fun deleteMany(ids: List<Long>)
}

@Database(
    entities = [
        PriceEntity::class, CollectionItem::class, Trade::class, TradeItem::class, Binder::class, ScannedCard::class,
        CmProduct::class, CmSetExpansion::class, WishlistItem::class, ValueSnapshot::class, SourcePrice::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun priceDao(): PriceDao
    abstract fun collectionDao(): CollectionDao
    abstract fun tradeDao(): TradeDao
    abstract fun binderDao(): BinderDao
    abstract fun scanDao(): ScanDao
    abstract fun catalogDao(): CatalogDao
    abstract fun wishlistDao(): WishlistDao
    abstract fun valueHistoryDao(): ValueHistoryDao
    abstract fun sourcePriceDao(): SourcePriceDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "poketrader.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()

        /** Version 5 (app 1.16): TCGplayer's prices, kept on the phone. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `source_prices` (`cardKey` TEXT NOT NULL, `source` TEXT NOT NULL, `price` REAL, " +
                        "`url` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`cardKey`, `source`))"
                )
            }
        }

        /** Version 4 (app 1.9): wishlist, value history, notes and purchase price. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `collection` ADD COLUMN `notes` TEXT")
                db.execSQL("ALTER TABLE `collection` ADD COLUMN `purchasePrice` REAL")
                V4_TABLES_SQL.forEach(db::execSQL)
            }
        }

        /** Version 3 (app 1.6): Cardmarket's product list, to check and fill in TCGdex's Cardmarket links. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                V3_TABLES_SQL.forEach(db::execSQL)
            }
        }

        /** Version 2 (app 1.5): binders and the Scan tab's waiting list. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `collection` ADD COLUMN `binderId` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("DROP INDEX IF EXISTS `index_collection_cardId_dataLang_variantId_condition_language`")
                db.execSQL("ALTER TABLE `trades` ADD COLUMN `binderId` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `trade_items` ADD COLUMN `appliedBinderId` INTEGER NOT NULL DEFAULT 0")
                V2_TABLES_SQL.forEach(db::execSQL)
            }
        }
    }
}

/** New tables of version 4, exactly as Room creates them (from the exported schema, schemas/…/4.json). */
private val V4_TABLES_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `wishlist` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`quantity` INTEGER NOT NULL, `anyVariant` INTEGER NOT NULL, `notes` TEXT, `addedAt` INTEGER NOT NULL, " +
        "`ownedAtAdd` INTEGER NOT NULL DEFAULT 0, `cardId` TEXT NOT NULL, `dataLang` TEXT NOT NULL, " +
        "`name` TEXT NOT NULL, `setId` TEXT NOT NULL, `setName` TEXT NOT NULL, `localId` TEXT NOT NULL, " +
        "`setOfficial` INTEGER, `rarity` TEXT NOT NULL, `imageBase` TEXT, `variantId` TEXT NOT NULL, " +
        "`variantLabel` TEXT NOT NULL, `cardmarketId` INTEGER, `holoPrice` INTEGER NOT NULL, `fallbackPrice` REAL, " +
        "`firstEdition` INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS `index_wishlist_cardId` ON `wishlist` (`cardId`)",
    "CREATE TABLE IF NOT EXISTS `value_history` (`day` TEXT NOT NULL, `cards` INTEGER NOT NULL, " +
        "`values` TEXT NOT NULL, PRIMARY KEY(`day`))",
)

/** New tables of version 3, exactly as Room creates them (copied from the generated AppDatabase_Impl). */
private val V3_TABLES_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `cm_products` (`idProduct` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
        "`idExpansion` INTEGER NOT NULL, PRIMARY KEY(`idProduct`))",
    "CREATE INDEX IF NOT EXISTS `index_cm_products_idExpansion` ON `cm_products` (`idExpansion`)",
    "CREATE TABLE IF NOT EXISTS `cm_set_expansions` (`key` TEXT NOT NULL, `idExpansion` INTEGER NOT NULL, PRIMARY KEY(`key`))",
)

/** New tables and index of version 2, exactly as Room creates them (copied from the generated AppDatabase_Impl). */
private val V2_TABLES_SQL = listOf(
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_collection_cardId_dataLang_variantId_condition_language_binderId` " +
        "ON `collection` (`cardId`, `dataLang`, `variantId`, `condition`, `language`, `binderId`)",
    "CREATE TABLE IF NOT EXISTS `binders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
        "`createdAt` INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS `scans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `condition` TEXT NOT NULL, " +
        "`language` TEXT NOT NULL, `quantity` INTEGER NOT NULL, `hasJumbo` INTEGER NOT NULL, `scannedAt` INTEGER NOT NULL, " +
        "`cardId` TEXT NOT NULL, `dataLang` TEXT NOT NULL, `name` TEXT NOT NULL, `setId` TEXT NOT NULL, `setName` TEXT NOT NULL, " +
        "`localId` TEXT NOT NULL, `setOfficial` INTEGER, `rarity` TEXT NOT NULL, `imageBase` TEXT, `variantId` TEXT NOT NULL, " +
        "`variantLabel` TEXT NOT NULL, `cardmarketId` INTEGER, `holoPrice` INTEGER NOT NULL, `fallbackPrice` REAL, " +
        "`firstEdition` INTEGER NOT NULL)",
)
