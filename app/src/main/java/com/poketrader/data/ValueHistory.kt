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
 * history. Since 1.9. Each binder's value is kept too (since 1.14), as extra "b:<binder>:<type>" entries
 * that older versions ignore.
 */
class ValueHistory(private val db: AppDatabase) {
    private val dao = db.valueHistoryDao()

    /** Saves today's value (replacing an earlier one from today). Nothing to save without prices. */
    suspend fun record(today: LocalDate = LocalDate.now()) {
        if (db.priceDao().countNow() == 0) return
        val rows = db.collectionDao().allWithPrices()
        if (rows.isEmpty()) return
        val binders = db.binderDao().all().map { it.id } + Binder.UNSORTED
        val values = JsonObject(snapshotValues(rows, binders).mapValues { JsonPrimitive(it.value) })
        dao.put(ValueSnapshot(today.toString(), rows.sumOf { it.item.quantity }, values.toString()))
    }

    /** The whole collection's value per day, or one [binder]'s (days before 1.14 have no binder values). */
    fun observe(type: PriceType, binder: Long? = null): Flow<List<ValuePoint>> = dao.observeAll().map { rows ->
        rows.mapNotNull { r ->
            val values = runCatching {
                (Json.parseToJsonElement(r.values) as JsonObject).mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }.toMap()
            }.getOrNull() ?: return@mapNotNull null
            point(LocalDate.parse(r.day), r.cards, values, type, binder)
        }
    }

    companion object {
        fun binderKey(binder: Long, what: String) = "b:$binder:$what"

        /** A day's numbers: the total per price type, and per binder its card count and value per price type. */
        fun snapshotValues(rows: List<CollectionRow>, binders: List<Long>): Map<String, Double> {
            val out = LinkedHashMap<String, Double>()
            PriceType.entries.forEach { t -> out[t.key] = totalValue(rows, t) }
            val byBinder = rows.groupBy { it.item.binderId }
            // Every binder, an empty one too, so its chart drops to zero instead of stopping.
            for (b in (binders + byBinder.keys).distinct()) {
                val rs = byBinder[b].orEmpty()
                out[binderKey(b, "cards")] = rs.sumOf { it.item.quantity }.toDouble()
                PriceType.entries.forEach { t -> out[binderKey(b, t.key)] = totalValue(rs, t) }
            }
            return out
        }

        /** Reads one point back from a day's numbers; null when the day has none for [binder]. */
        fun point(day: LocalDate, cards: Int, values: Map<String, Double>, type: PriceType, binder: Long?): ValuePoint? =
            if (binder == null) values[type.key]?.let { ValuePoint(day, it, cards) }
            else values[binderKey(binder, type.key)]?.let { v -> ValuePoint(day, v, values[binderKey(binder, "cards")]?.toInt() ?: 0) }

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
