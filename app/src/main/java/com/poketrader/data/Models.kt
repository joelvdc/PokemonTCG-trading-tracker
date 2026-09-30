package com.poketrader.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import kotlin.math.abs
import kotlin.math.max

/** Which Cardmarket price-guide figure is used to value cards. */
enum class PriceType(val key: String, val label: String, val short: String) {
    TREND("trend", "Trend price", "Trend"),
    AVG("avg", "Average sell price", "Average"),
    AVG30("avg30", "30-day average", "30-day avg"),
    AVG7("avg7", "7-day average", "7-day avg"),
    AVG1("avg1", "1-day average", "1-day avg"),
    LOW("low", "Lowest listing", "Low");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: TREND
    }
}

/** One set of Cardmarket price-guide numbers, in EUR. */
data class PriceSet(
    val trend: Double? = null,
    val avg: Double? = null,
    val low: Double? = null,
    val avg1: Double? = null,
    val avg7: Double? = null,
    val avg30: Double? = null,
) {
    fun get(type: PriceType): Double? = when (type) {
        PriceType.TREND -> trend
        PriceType.AVG -> avg
        PriceType.LOW -> low
        PriceType.AVG1 -> avg1
        PriceType.AVG7 -> avg7
        PriceType.AVG30 -> avg30
    }

    /** The requested figure, falling back to the most stable one available. */
    fun best(type: PriceType): Double? = get(type) ?: trend ?: avg ?: avg30 ?: avg7 ?: avg1 ?: low

    val isEmpty get() = PriceType.entries.all { get(it) == null }

    /** How the price is moving: Cardmarket's trend price vs its 30-day average, or null without both. */
    val trendChange: PriceTrend? get() = PriceTrend.of(trend, avg30)
}

/** A price movement in percent, e.g. +12.3 when the trend is 12.3% above the 30-day average. */
data class PriceTrend(val pct: Double) {
    val up get() = pct > 0
    val flat get() = pct == 0.0

    /** "▲ 12.3%", "▼ 0.1%" or "▬ 0.0%": one decimal, and never less than 0.1% for a real change. */
    fun label(locale: java.util.Locale = java.util.Locale.getDefault()): String {
        if (flat) return "▬ " + String.format(locale, "%.1f%%", 0.0)
        val shown = maxOf(abs(pct), 0.1)
        return (if (up) "▲ " else "▼ ") + String.format(locale, "%.1f%%", shown)
    }

    companion object {
        fun of(trend: Double?, avg30: Double?): PriceTrend? {
            if (trend == null || avg30 == null || avg30 <= 0) return null
            return PriceTrend((trend - avg30) / avg30 * 100)
        }
    }
}

/**
 * A row of Cardmarket's daily Pokémon price guide, keyed by Cardmarket product id.
 * Cardmarket records normal copies and holo/reverse-holo copies of a product separately.
 */
@Entity(tableName = "prices")
data class PriceEntity(
    @PrimaryKey val idProduct: Int,
    val avg: Double?,
    val low: Double?,
    val trend: Double?,
    val avg1: Double?,
    val avg7: Double?,
    val avg30: Double?,
    val avgHolo: Double?,
    val lowHolo: Double?,
    val trendHolo: Double?,
    val avg1Holo: Double?,
    val avg7Holo: Double?,
    val avg30Holo: Double?,
) {
    private fun normal() = PriceSet(trend = trend, avg = avg, low = low, avg1 = avg1, avg7 = avg7, avg30 = avg30)
    private fun holo() = PriceSet(trend = trendHolo, avg = avgHolo, low = lowHolo, avg1 = avg1Holo, avg7 = avg7Holo, avg30 = avg30Holo)

    /** Prices for one variant; falls back to the other column when Cardmarket only filled one. */
    fun toSet(holo: Boolean): PriceSet {
        val primary = if (holo) holo() else normal()
        return if (primary.isEmpty) (if (holo) normal() else holo()) else primary
    }
}

/**
 * One exact printing: a TCGdex card in one variant (normal, reverse holo, Poké Ball pattern,
 * 1st Edition…). International (English/European) cards use TCGdex's English data;
 * Japanese cards are separate sets with their own ids and Cardmarket products.
 */
data class CardRef(
    val cardId: String,
    /** "en" for international prints, "ja" for Japanese prints. */
    val dataLang: String,
    val name: String,
    val setId: String,
    val setName: String,
    val localId: String,
    val setOfficial: Int?,
    val rarity: String,
    /** TCGdex image base URL; append "/low.webp" or "/high.webp". */
    val imageBase: String?,
    val variantId: String,
    val variantLabel: String,
    val cardmarketId: Int?,
    /** Use Cardmarket's holo columns for this variant (reverse holos). */
    val holoPrice: Boolean,
    /** Cardmarket trend as TCGdex last saw it — used when the price guide has no entry. */
    val fallbackPrice: Double?,
    val firstEdition: Boolean,
) {
    /** Jumbo / oversized print; derived from the label so no extra database column is needed. */
    val oversized get() = OVERSIZED_LABELS.any { variantLabel.startsWith(it) }

    /** Picture URLs: TCGdex's, or pokemontcg.io's when TCGdex has none (see [FallbackImages]). */
    val thumbUrl get() = FallbackImages.thumb(FallbackImages.base(imageBase, dataLang, setId, localId))
    val largeUrl get() = FallbackImages.large(FallbackImages.base(imageBase, dataLang, setId, localId))
    val numberLabel get() = setOfficial?.let { "$localId/$it" } ?: localId
    val isJapanese get() = dataLang == "ja"

    companion object {
        val OVERSIZED_LABELS = listOf("Jumbo", "Oversized")
    }
}

@Entity(
    tableName = "collection",
    indices = [
        Index(value = ["cardId", "dataLang", "variantId", "condition", "language", "binderId"], unique = true),
        Index("name"),
    ],
)
data class CollectionItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Embedded val card: CardRef,
    val condition: String = "NM",
    val language: String = "EN",
    val quantity: Int,
    val addedAt: Long = System.currentTimeMillis(),
    /** The [Binder] this stack is in, or [Binder.UNSORTED]. Since version 1.5. */
    @ColumnInfo(defaultValue = "0") val binderId: Long = Binder.UNSORTED,
)

/** A named group of cards in "My cards", like a real binder. Since version 1.5. */
@Entity(tableName = "binders")
data class Binder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /** Cards that aren't in any binder. */
        const val UNSORTED = 0L
        const val UNSORTED_NAME = "Unsorted"
    }
}

/** Where cards should go: an existing binder, Unsorted, or a binder still to be created with [newName]. */
data class BinderChoice(val binderId: Long = Binder.UNSORTED, val newName: String? = null)

/** A scanned card waiting in the Scan tab until it's sent to a binder or trade, or discarded. Since 1.5. */
@Entity(tableName = "scans")
data class ScannedCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Embedded val card: CardRef,
    val condition: String = "NM",
    val language: String = "EN",
    val quantity: Int = 1,
    /** The card also exists as a jumbo print; the camera can't tell the size, so the list offers a check. */
    val hasJumbo: Boolean = false,
    val scannedAt: Long = System.currentTimeMillis(),
)

/** Scanned card joined with today's price guide entry. */
data class ScanRow(
    @Embedded val item: ScannedCard,
    @Embedded(prefix = "pr_") val price: PriceEntity?,
) {
    fun unitPrice(type: PriceType): Double? =
        price?.toSet(item.card.holoPrice)?.best(type) ?: item.card.fallbackPrice

    val trend: PriceTrend? get() = price?.toSet(item.card.holoPrice)?.trendChange
}

/** Collection row joined with today's price guide entry. */
data class CollectionRow(
    @Embedded val item: CollectionItem,
    @Embedded(prefix = "pr_") val price: PriceEntity?,
) {
    fun unitPrice(type: PriceType): Double? =
        price?.toSet(item.card.holoPrice)?.best(type) ?: item.card.fallbackPrice

    val trend: PriceTrend? get() = price?.toSet(item.card.holoPrice)?.trendChange
}

@Entity(tableName = "trades")
data class Trade(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val partner: String = "",
    val notes: String = "",
    val applied: Boolean = false,
    val appliedAt: Long? = null,
    /** The binder the cards you got went into when the trade was applied. Since version 1.5. */
    @ColumnInfo(defaultValue = "0") val binderId: Long = Binder.UNSORTED,
)

object Side {
    /** Cards you receive. */
    const val GET = "GET"

    /** Cards you hand over. */
    const val GIVE = "GIVE"
}

@Entity(
    tableName = "trade_items",
    foreignKeys = [ForeignKey(entity = Trade::class, parentColumns = ["id"], childColumns = ["tradeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tradeId")],
)
data class TradeItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tradeId: Long,
    val side: String,
    @Embedded val card: CardRef,
    val condition: String = "NM",
    val language: String = "EN",
    val quantity: Int = 1,
    /** Price guide values captured when the card was added (or last refreshed). */
    @Embedded(prefix = "p_") val prices: PriceSet = PriceSet(),
    /** Optional manually agreed price per copy, overriding the price guide. */
    val customPrice: Double? = null,
    /** How many copies were actually added (+) / removed (−) from the collection when the trade was applied. */
    val appliedDelta: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    /** For given cards: the binder they were (mostly) taken from, so undoing puts them back there. Since 1.5. */
    @ColumnInfo(defaultValue = "0") val appliedBinderId: Long = Binder.UNSORTED,
) {
    fun unitPrice(type: PriceType): Double? = customPrice ?: prices.best(type) ?: card.fallbackPrice
    fun lineTotal(type: PriceType): Double = (unitPrice(type) ?: 0.0) * quantity
}

data class TradeWithItems(
    @Embedded val trade: Trade,
    @Relation(parentColumn = "id", entityColumn = "tradeId") val items: List<TradeItem>,
) {
    val give get() = items.filter { it.side == Side.GIVE }
    val get get() = items.filter { it.side == Side.GET }
    fun balance(type: PriceType, tolerancePct: Int) = Balance.of(items, type, tolerancePct)
}

data class OwnedCount(val cardId: String, val qty: Int)

enum class Verdict { EMPTY, FAIR, FAVORS_YOU, FAVORS_THEM }

/** Value of both sides of a trade and how far apart they are. */
data class Balance(val give: Double, val get: Double, val tolerancePct: Int) {
    /** Positive when you receive more value than you hand over. */
    val diff get() = get - give

    /** Difference relative to the bigger side, in percent. */
    val pct: Double
        get() {
            val m = max(give, get)
            return if (m > 0) diff / m * 100 else 0.0
        }

    val verdict: Verdict
        get() = when {
            give == 0.0 && get == 0.0 -> Verdict.EMPTY
            abs(pct) <= tolerancePct -> Verdict.FAIR
            diff > 0 -> Verdict.FAVORS_YOU
            else -> Verdict.FAVORS_THEM
        }

    companion object {
        fun of(items: List<TradeItem>, type: PriceType, tolerancePct: Int) = Balance(
            give = items.filter { it.side == Side.GIVE }.sumOf { it.lineTotal(type) },
            get = items.filter { it.side == Side.GET }.sumOf { it.lineTotal(type) },
            tolerancePct = tolerancePct,
        )
    }
}

val CONDITIONS = listOf(
    "MT" to "Mint",
    "NM" to "Near Mint",
    "EX" to "Excellent",
    "GD" to "Good",
    "LP" to "Light Played",
    "PL" to "Played",
    "PO" to "Poor",
)

val LANGUAGES = listOf(
    "EN" to "English", "JA" to "Japanese", "FR" to "French", "DE" to "German", "IT" to "Italian",
    "ES" to "Spanish", "PT" to "Portuguese", "KO" to "Korean", "ZH" to "Chinese",
)
