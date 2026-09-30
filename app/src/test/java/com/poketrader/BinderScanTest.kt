package com.poketrader

import com.poketrader.data.Binder
import com.poketrader.data.CardTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class CardTargetTest {
    @Test
    fun encodesAndDecodesEveryTarget() {
        val targets = listOf(
            CardTarget.TradeSide(3, "GET"), CardTarget.Collection(), CardTarget.Collection(12), CardTarget.Scans,
            CardTarget.ReplaceTradeItem(4), CardTarget.ReplaceCollectionItem(5), CardTarget.ReplaceScan(6),
        )
        targets.forEach { assertEquals(it, CardTarget.decode(it.encode())) }
        // Links saved before binders existed.
        assertEquals(CardTarget.Collection(Binder.UNSORTED), CardTarget.decode("collection"))
    }
}
