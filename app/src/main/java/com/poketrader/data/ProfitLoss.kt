package com.poketrader.data

/** A card bought at [paid] and worth [now] today, per copy, in EUR. */
data class Buy(val row: CollectionRow, val paid: Double, val now: Double) {
    val gain get() = now - paid
    val pct get() = if (paid > 0) gain / paid * 100 else 0.0
}

/**
 * What was paid for the cards against what they are worth now. Only copies with a purchase price
 * (and a price today) count: [counted] of [copies]. [best] and [worst] rank one copy's gain or
 * loss, like the price changes do. Since 1.20.
 */
data class ProfitLoss(val paid: Double, val worth: Double, val counted: Int, val copies: Int, val best: List<Buy>, val worst: List<Buy>) {
    val gain get() = worth - paid

    companion object {
        fun of(rows: List<CollectionRow>, type: PriceType, count: Int = 5): ProfitLoss {
            val buys = rows.mapNotNull { r ->
                val paid = r.item.purchaseEur ?: return@mapNotNull null
                val now = r.unitPrice(type) ?: return@mapNotNull null
                Buy(r, paid, now)
            }
            return ProfitLoss(
                paid = buys.sumOf { it.paid * it.row.item.quantity },
                worth = buys.sumOf { it.now * it.row.item.quantity },
                counted = buys.sumOf { it.row.item.quantity },
                copies = rows.sumOf { it.item.quantity },
                best = buys.filter { it.gain > 0.004 }.sortedByDescending { it.gain }.take(count),
                worst = buys.filter { it.gain < -0.004 }.sortedBy { it.gain }.take(count),
            )
        }
    }
}
