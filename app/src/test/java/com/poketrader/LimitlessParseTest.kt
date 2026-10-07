package com.poketrader

import com.poketrader.data.EnglishPrint
import com.poketrader.data.FallbackImages
import com.poketrader.data.LimitlessCards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Limitless TCG card pages saved in October 2026 (cards TCGdex doesn't have). */
class LimitlessParseTest {
    private fun page(name: String) = javaClass.getResource("/limitless/$name.html")!!.readText()

    @Test fun pokemonGoTyranitar() {
        val p = LimitlessCards.parse(page("jp_S10b_43"))!!
        assertEquals("バンギラス", p.name)
        assertEquals("Pokémon GO", p.setName)
        assertEquals("Rare", p.rarity)
        assertEquals(listOf(EnglishPrint("PGO", "Pokémon GO", "43", 0.27)), p.english)
        assertEquals("https://limitlesstcg.nyc3.cdn.digitaloceanspaces.com/tpc/S10b/S10b_43_R_JP", p.imageBase)
        assertEquals(p.imageBase + "_SM.png", FallbackImages.thumb(p.imageBase))
        assertEquals(p.imageBase + "_LG.png", FallbackImages.large(p.imageBase))
    }

    @Test fun shinyTreasureDittoPointsAtItsEnglishPrint() {
        val p = LimitlessCards.parse(page("jp_SV4a_144"))!!
        assertEquals("メタモン", p.name)
        assertEquals("Shiny Treasure ex", p.setName)
        assertEquals(listOf("MEW 132", "PAF 201"), p.english.map { "${it.code} ${it.number}" })
        assertEquals("Pokémon 151", p.english.first().setName)
    }

    @Test fun eeveeHeroesVaporeon() {
        val p = LimitlessCards.parse(page("jp_S6a_15"))!!
        assertEquals("シャワーズV", p.name)
        assertEquals("Eevee Heroes", p.setName)
        assertEquals("Double Rare", p.rarity)
        // Two promos and Evolving Skies; the regular set is tried first.
        assertEquals(listOf("SP 150", "SP 181", "EVS 172"), p.english.map { "${it.code} ${it.number}" })
        assertEquals(listOf(true, true, false), p.english.map { it.isPromo })
    }

    @Test fun notACardPage() = assertNull(LimitlessCards.parse("<html><title>Page not found</title></html>"))
}
