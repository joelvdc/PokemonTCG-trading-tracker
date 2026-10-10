package com.poketrader.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A card you want. By default any variant of the card will do (normal, reverse holo…); with
 * [anyVariant] off, only this exact variant. Shown as the Wishlist in the collection, not counted in
 * the collection's value. Since 1.9.
 */
@Entity(tableName = "wishlist", indices = [Index("cardId")])
data class WishlistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Embedded val card: CardRef,
    val quantity: Int = 1,
    val anyVariant: Boolean = true,
    val notes: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** Copies you already had when it was added, so only copies you get later take it off the list. */
    @ColumnInfo(defaultValue = "0") val ownedAtAdd: Int = 0,
)

/** Wishlist entry joined with today's price guide entry. */
data class WishlistRow(
    @Embedded val item: WishlistItem,
    @Embedded(prefix = "pr_") val price: PriceEntity?,
) {
    fun unitPrice(type: PriceType): Double? = Pricing.unit(item.card, price?.toSet(item.card.holoPrice)?.best(type) ?: item.card.fallbackPrice)
}

/** A wishlist entry against the collection: copies owned now, and copies got since it was added. */
data class WishlistOwned(val owned: Int, val gotSince: Int)

@Dao
interface WishlistDao {
    @Query(
        """SELECT w.*, p.idProduct AS pr_idProduct, p.avg AS pr_avg, p.low AS pr_low, p.trend AS pr_trend,
           p.avg1 AS pr_avg1, p.avg7 AS pr_avg7, p.avg30 AS pr_avg30, p.avgHolo AS pr_avgHolo,
           p.lowHolo AS pr_lowHolo, p.trendHolo AS pr_trendHolo, p.avg1Holo AS pr_avg1Holo,
           p.avg7Holo AS pr_avg7Holo, p.avg30Holo AS pr_avg30Holo
           FROM wishlist w LEFT JOIN prices p ON p.idProduct = w.cardmarketId
           ORDER BY w.name COLLATE NOCASE"""
    )
    fun observeAll(): Flow<List<WishlistRow>>

    @Query("SELECT * FROM wishlist")
    suspend fun all(): List<WishlistItem>

    @Query("SELECT * FROM wishlist WHERE id = :id")
    suspend fun byId(id: Long): WishlistItem?

    @Query("SELECT * FROM wishlist WHERE cardId = :cardId AND dataLang = :dataLang")
    suspend fun byCard(cardId: String, dataLang: String): List<WishlistItem>

    @Insert
    suspend fun insert(item: WishlistItem): Long

    @Update
    suspend fun update(item: WishlistItem)

    @Query("DELETE FROM wishlist WHERE id = :id")
    suspend fun delete(id: Long)
}

/** The collection's value on one day, for each price type (JSON: price type key → EUR). Since 1.9. */
@Entity(tableName = "value_history")
data class ValueSnapshot(
    /** "2026-10-04" */
    @PrimaryKey val day: String,
    val cards: Int,
    val values: String,
)

@Dao
interface ValueHistoryDao {
    @Query("SELECT * FROM value_history ORDER BY day")
    fun observeAll(): Flow<List<ValueSnapshot>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: ValueSnapshot)
}
