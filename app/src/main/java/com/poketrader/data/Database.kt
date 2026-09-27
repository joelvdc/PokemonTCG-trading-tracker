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

    @Query("SELECT cardId, SUM(quantity) AS qty FROM collection GROUP BY cardId")
    fun observeOwned(): Flow<List<OwnedCount>>

    @Query(
        """SELECT * FROM collection WHERE cardId = :cardId AND dataLang = :dataLang AND variantId = :variantId
           AND condition = :cond AND language = :lang LIMIT 1"""
    )
    suspend fun find(cardId: String, dataLang: String, variantId: String, cond: String, lang: String): CollectionItem?

    @Query("SELECT * FROM collection WHERE cardId = :cardId AND dataLang = :dataLang AND variantId = :variantId ORDER BY quantity DESC")
    suspend fun findAny(cardId: String, dataLang: String, variantId: String): List<CollectionItem>

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
}

@Database(
    entities = [PriceEntity::class, CollectionItem::class, Trade::class, TradeItem::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun priceDao(): PriceDao
    abstract fun collectionDao(): CollectionDao
    abstract fun tradeDao(): TradeDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "poketrader.db").build()
    }
}
