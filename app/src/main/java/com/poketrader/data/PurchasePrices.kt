package com.poketrader.data

import kotlin.math.roundToLong

/**
 * Purchase prices of stacks that join (the same card, version, condition and language ending up in one
 * binder): the stack gets the average paid, weighted by copies. Prices are kept in the currency they
 * were typed in; when the two are in different currencies, the person picks the one to keep and both
 * are converted to it at today's rate. Since 1.20.
 */
object PurchasePrices {
    fun cents(v: Double) = (v * 100).roundToLong() / 100.0

    /** The currency a kept price is in (null = euros). */
    fun currencyOf(code: String?): AppCurrency = AppCurrency.entries.firstOrNull { it.name == code } ?: AppCurrency.EUR

    /** Whether two stacks' prices are in different currencies, so joining them needs a choice. */
    fun clash(a: CollectionItem, b: CollectionItem): Boolean =
        a.purchasePrice != null && b.purchasePrice != null && currencyOf(a.purchaseCurrency) != currencyOf(b.purchaseCurrency)

    /**
     * The price of a stack after [added] copies bought at [paid] (in [paidCurrency]) join [had] copies at
     * [hadPrice] (in [hadCurrency]): the average weighted by copies when both have a price, else whichever
     * is known. In different currencies, the average is in [into] (the stack's own currency if not given).
     * Returns the amount and its currency code (null = euros).
     */
    fun joined(
        had: Int, hadPrice: Double?, hadCurrency: String?,
        added: Int, paid: Double?, paidCurrency: String?,
        into: AppCurrency? = null,
    ): Pair<Double?, String?> {
        if (paid == null) return hadPrice to hadCurrency
        if (hadPrice == null || had <= 0) return paid to paidCurrency
        val a = currencyOf(hadCurrency)
        val b = currencyOf(paidCurrency)
        if (a == b) return cents((had * hadPrice + added * paid) / (had + added)) to hadCurrency
        val target = into ?: a
        fun inTarget(amount: Double, c: AppCurrency): Double? =
            if (c == target) amount else Money.eurOf(amount, c.name)?.let { Money.amountIn(it, target) }
        val x = inTarget(hadPrice, a) ?: return hadPrice to hadCurrency
        val y = inTarget(paid, b) ?: return hadPrice to hadCurrency
        return cents((had * x + added * y) / (had + added)) to code(target)
    }

    /** How a currency is kept with a price: null for euros. */
    fun code(c: AppCurrency): String? = c.name.takeIf { c != AppCurrency.EUR }
}
