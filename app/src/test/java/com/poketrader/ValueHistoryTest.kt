package com.poketrader

import com.poketrader.data.Binder
import com.poketrader.data.CardRef
import com.poketrader.data.CollectionItem
import com.poketrader.data.CollectionRow
import com.poketrader.data.PriceType
import com.poketrader.data.ValueHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ValueHistoryTest {
    private fun row(price: Double, qty: Int, binder: Long) = CollectionRow(
        CollectionItem(
            card = CardRef(
                cardId = "x-$price", dataLang = "en", name = "X", setId = "x", setName = "X", localId = "1", setOfficial = null,
                rarity = "", imageBase = null, variantId = "normal", variantLabel = "Normal", cardmarketId = null, holoPrice = false,
                fallbackPrice = price, firstEdition = false,
            ),
            quantity = qty, binderId = binder,
        ),
        null,
    )

    @Test fun keepsEachBindersValue() {
        val rows = listOf(row(10.0, 2, 5L), row(1.0, 3, Binder.UNSORTED))
        val values = ValueHistory.snapshotValues(rows, binders = listOf(5L, 7L, Binder.UNSORTED))
        val day = LocalDate.of(2026, 10, 9)
        assertEquals(23.0, ValueHistory.point(day, 5, values, PriceType.TREND, null)!!.value, 1e-9)
        val five = ValueHistory.point(day, 5, values, PriceType.TREND, 5L)!!
        assertEquals(20.0, five.value, 1e-9)
        assertEquals(2, five.cards)
        assertEquals(3.0, ValueHistory.point(day, 5, values, PriceType.TREND, Binder.UNSORTED)!!.value, 1e-9)
        // An empty binder is saved as zero, so its chart doesn't just stop.
        assertEquals(0.0, ValueHistory.point(day, 5, values, PriceType.TREND, 7L)!!.value, 1e-9)
        // Days saved before 1.14 only have the total.
        assertNull(ValueHistory.point(day, 5, mapOf("trend" to 23.0), PriceType.TREND, 5L))
    }
}
