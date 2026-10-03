package com.poketrader.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate

/** One day's collection value in the chosen price type. */
data class ValuePoint(val day: LocalDate, val value: Double, val cards: Int)

/** A card whose price moved: Cardmarket's trend vs its 30-day average, times the copies you own. */
data class PriceMove(val row: CollectionRow, val change: Double, val pct: Double)

/**
 * The collection's value over time: once a day (when the app opens and after each price update)
 * the total is worked out for every price type and kept, so switching the price type keeps the
 * history. Since 1.9.
 */
class ValueHistory(private val db: AppDatabase) {
    private val dao = db.valueHistoryDao()

    /** Saves today's value (replacing an earlier one from today). Nothing to save without prices. */
    suspend fun record(today: LocalDate = LocalDate.now()) {
        if (db.priceDao().countNow() == 0) return
        val rows = db.collectionDao().allWithPrices()
        if (rows.isEmpty()) return
        val values = JsonObject(PriceType.entries.associate { t -> t.key to JsonPrimitive(totalValue(rows, t)) })
        dao.put(ValueSnapshot(today.toString(), rows.sumOf { it.item.quantity }, values.toString()))
    }

    fun observe(type: PriceType): Flow<List<ValuePoint>> = dao.observeAll().map { rows ->
        rows.mapNotNull { r ->
            val v = runCatching { (Json.parseToJsonElement(r.values) as JsonObject)[type.key] as? JsonPrimitive }.getOrNull()?.doubleOrNull
                ?: return@mapNotNull null
            ValuePoint(LocalDate.parse(r.day), v, r.cards)
        }
    }

    companion object {
        fun totalValue(rows: List<CollectionRow>, type: PriceType) = rows.sumOf { (it.unitPrice(type) ?: 0.0) * it.item.quantity }

        /** The cards that gained (or lost) the most value lately, in EUR over all copies you own. */
        fun biggestMoves(rows: List<CollectionRow>, up: Boolean, count: Int = 5): List<PriceMove> =
            rows.mapNotNull { r ->
                val p = r.price?.toSet(r.item.card.holoPrice) ?: return@mapNotNull null
                val trend = p.trend ?: return@mapNotNull null
                val avg30 = p.avg30?.takeIf { it > 0 } ?: return@mapNotNull null
                PriceMove(r, (trend - avg30) * r.item.quantity, (trend - avg30) / avg30 * 100)
            }
                .filter { if (up) it.change > 0 else it.change < 0 }
                .sortedBy { if (up) -it.change else it.change }
                .take(count)
    }
}
