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
import kotlinx.serialization.Serializable

/**
 * A card you want. By default any variant of the card will do (normal, reverse holo…); with
 * [anyVariant] off, only this exact variant. Shown as the Wishlist in the collection, not counted in
 * the collection's value. Since 1.9.
 */
@Serializable
@Entity(tableName = "wishlist", indices = [Index("cardId"), Index("uid")])
data class WishlistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Embedded val card: CardRef,
    val quantity: Int = 1,
    val anyVariant: Boolean = true,
    val notes: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** Copies you already had when it was added, so only copies you get later take it off the list. */
    @ColumnInfo(defaultValue = "0") val ownedAtAdd: Int = 0,
    /** When you ticked "Ordered, on its way"; null while it isn't ordered yet. Since 1.20. */
    val orderedAt: Long? = null,
    /** Notify when the card costs this much or less, in [alertCurrency] (as typed); null for no notifications. Since 1.20. */
    val alertBelow: Double? = null,
    /** The currency [alertBelow] was typed in ("DKK"); null for euros. Since 1.20. */
    val alertCurrency: String? = null,
    /**
     * When it arrived ("Received", swiped right): the card is then kept in the wishlist's Received section
     * as a record, no longer wanted (not counted, alerted, shared or starred). Since 1.20.
     */
    val receivedAt: Long? = null,
    /** "added to the collection" once a received card's copies went into the collection, so they aren't added twice. Since 1.20. */
    val receivedFrom: String? = null,
    /** Sync identity and last change; filled in by database triggers (see [SyncSchema]). Since 1.20. */
    val uid: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
) {
    val isPending get() = PendingCard.isPending(card.cardId)
}

/** The alert level in euros at today's rate (see [WishlistItem.alertCurrency]); null without a level. Since 1.20. */
val WishlistItem.levelEur: Double? get() = alertBelow?.let { Money.eurOf(it, alertCurrency) }

/**
 * A wishlist card added while offline, known only by its name (from Cardmarket's product list on the
 * phone, so an English name). Its set, picture and prices are filled in from TCGdex once the phone is
 * back online. Since 1.20.
 */
object PendingCard {
    const val ID_PREFIX = "pending:"

    fun isPending(cardId: String) = cardId.startsWith(ID_PREFIX)

    fun ref(name: String) = CardRef(
        cardId = ID_PREFIX + name.lowercase(),
        dataLang = "en",
        name = name,
        setId = "",
        setName = "",
        localId = "",
        setOfficial = null,
        rarity = "",
        imageBase = null,
        variantId = "",
        variantLabel = "",
        cardmarketId = null,
        holoPrice = false,
        fallbackPrice = null,
        firstEdition = false,
    )
}

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

    /** The cards still wanted (not received). Since 1.20. */
    @Query("SELECT * FROM wishlist WHERE receivedAt IS NULL")
    suspend fun wanted(): List<WishlistItem>

    @Query("SELECT * FROM wishlist WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<WishlistItem>

    @Query("SELECT * FROM wishlist WHERE receivedAt IS NOT NULL AND receivedAt < :before")
    suspend fun receivedBefore(before: Long): List<WishlistItem>

    @Query("SELECT * FROM wishlist WHERE cardId LIKE 'pending:%'")
    suspend fun pending(): List<WishlistItem>

    @Query("DELETE FROM wishlist WHERE id IN (:ids)")
    suspend fun deleteMany(ids: List<Long>)

    /** The wanted (not received) entries for a card. */
    @Query("SELECT * FROM wishlist WHERE cardId = :cardId AND dataLang = :dataLang AND receivedAt IS NULL")
    suspend fun byCard(cardId: String, dataLang: String): List<WishlistItem>

    @Insert
    suspend fun insert(item: WishlistItem): Long

    @Update
    suspend fun update(item: WishlistItem)

    @Query("DELETE FROM wishlist WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * A wishlist card's latest price for its alert, kept on this phone only (each phone looks prices up and
 * notifies on its own). Since 1.20.
 */
@Entity(tableName = "wish_alerts")
data class WishAlert(
    @PrimaryKey val wishId: Long,
    /** What [price] is ([PriceAlerts.basis]); after a change in Settings it's unused until the next check. */
    val basis: String = "",
    /** The cheapest version's price, in euros, and that version's label ("Reverse Holo"). */
    val price: Double? = null,
    val variantLabel: String? = null,
    /** "Any version": the card's versions with their Cardmarket products ([WishVariant], JSON), looked up daily. */
    val variants: String? = null,
    val variantsAt: Long = 0,
    /** The level a notification went out for, until the price is above it again. */
    val alertedLevel: Double? = null,
    val checkedAt: Long = 0,
)

@Dao
interface WishAlertDao {
    @Query("SELECT * FROM wish_alerts")
    fun observeAll(): Flow<List<WishAlert>>

    @Query("SELECT * FROM wish_alerts WHERE wishId = :id")
    suspend fun get(id: Long): WishAlert?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: WishAlert)

    @Query("DELETE FROM wish_alerts WHERE wishId NOT IN (:ids)")
    suspend fun keepOnly(ids: List<Long>)

    @Query("DELETE FROM wish_alerts")
    suspend fun clear()
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
