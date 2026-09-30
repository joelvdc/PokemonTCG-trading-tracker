package com.poketrader

import com.poketrader.data.CardmarketFix
import com.poketrader.data.CatalogMatch
import com.poketrader.data.CmProduct
import com.poketrader.data.TcgCard
import com.poketrader.data.TcgCardSet
import com.poketrader.data.TcgCardmarketPricing
import com.poketrader.data.TcgPricing
import com.poketrader.data.TcgThirdParty
import com.poketrader.data.TcgVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMatchTest {
    @Test
    fun readsCardmarketProductNames() {
        assertEquals("Nidoran ♂", CatalogMatch.baseName("Nidoran [M] [Horn Hazard]"))
        assertEquals(listOf("Horn Hazard"), CatalogMatch.attacks("Nidoran [M] [Horn Hazard]"))
        assertEquals(listOf("Poison Vine", "Vine Whip"), CatalogMatch.attacks("Erika's Bellsprout [Poison Vine | Vine Whip]"))
        assertEquals(emptyList<String>(), CatalogMatch.attacks("Erika"))
    }

    @Test
    fun matchesNamesTheWayCardmarketWritesThem() {
        assertTrue(CatalogMatch.sameCard("Nidoran ♂", "Nidoran [M] [Horn Hazard]"))
        assertFalse(CatalogMatch.sameCard("Nidoran ♀", "Nidoran [M] [Horn Hazard]"))
        assertTrue(CatalogMatch.sameCard("Dialga", "Dialga Lv.68 [Time Bellow | Flash Cannon]"))
        assertTrue(CatalogMatch.sameCard("Charmander δ", "Charmander δ Delta Species [Scratch | Bite]"))
        assertTrue(CatalogMatch.sameCard("Team Magma's Groudon", "Team Magma's Groudon [Pulverize]"))
        // TCGdex links that point to another card.
        assertFalse(CatalogMatch.sameCard("Erika's Bellsprout", "Erika"))
        assertFalse(CatalogMatch.sameCard("Giratina VSTAR", "Giratina V [Abyss Seeking | Shred]"))
    }

    @Test
    fun tellsSameNamedCardsApartByAttacks() {
        val attacks = listOf("Flamethrower", "Flare Blitz GX")
        assertTrue(CatalogMatch.isThisCard("Charizard GX", attacks, "Charizard GX [Flamethrower | Flare Blitz GX]"))
        assertFalse(CatalogMatch.isThisCard("Charizard GX", attacks, "Charizard GX [Wing Attack | Crimson Storm | Raging Out GX]"))
        // Trainers list no attacks: the name decides.
        assertTrue(CatalogMatch.isThisCard("Potion", emptyList(), "Potion"))
        val hiddenFates = listOf(
            CmProduct(381243, "Charizard GX [Wing Attack | Crimson Storm | Raging Out GX]", 2514),
            CmProduct(394737, "Charizard GX [Flamethrower | Flare Blitz GX]", 2514),
        )
        assertEquals(394737, CatalogMatch.pick(hiddenFates, "Charizard GX", attacks))
    }

    @Test
    fun picksTheProductByNameAndAttacks() {
        val gymHeroes = listOf(
            CmProduct(274152, "Erika", 1529),
            CmProduct(274211, "Erika's Bellsprout [Poison Vine | Vine Whip]", 1529),
            CmProduct(274212, "Erika's Bellsprout [Careless Tackle]", 1529),
        )
        assertEquals(274212, CatalogMatch.pick(gymHeroes, "Erika's Bellsprout", listOf("Careless Tackle")))
        assertEquals(274211, CatalogMatch.pick(gymHeroes, "Erika's Bellsprout", listOf("Vine Whip")))
        // Attacks that fit neither: better no link than a wrong one.
        assertNull(CatalogMatch.pick(gymHeroes, "Erika's Bellsprout", listOf("Razor Leaf")))
        assertEquals(274152, CatalogMatch.pick(gymHeroes, "Erika", emptyList()))

        // Reprint products with identical names: the original one.
        val base = listOf(
            CmProduct(660182, "Charmander [Scratch | Ember]", 1523),
            CmProduct(273741, "Charmander [Scratch | Ember]", 1523),
        )
        assertEquals(273741, CatalogMatch.pick(base, "Charmander", listOf("Scratch", "Ember")))

        // Two different trainers of the same name can't be told apart: no guess.
        val research = listOf(
            CmProduct(1, "Professor's Research [Sada]", 9),
            CmProduct(2, "Professor's Research [Turo]", 9),
        )
        assertNull(CatalogMatch.pick(research, "Professor's Research", emptyList()))
    }
}

class CardLevelLinkTest {
    private fun card(variants: List<TcgVariant>, cardLevel: Int?, fixes: Map<String, CardmarketFix> = emptyMap()) = TcgCard(
        id = "ex14-61", localId = "61", name = "Spearow", set = TcgCardSet("ex14", "Crystal Guardians"),
        variantsDetailed = variants,
        thirdParty = cardLevel?.let { TcgThirdParty(cardmarket = it) },
        pricing = cardLevel?.let { TcgPricing(TcgCardmarketPricing(idProduct = it, trend = 0.25)) },
        cardmarketFixes = fixes,
    )

    @Test
    fun plainVariantsWithoutTheirOwnLinkUseTheCardsLink() {
        val c = card(
            listOf(
                TcgVariant("normal", variantId = "plain"),
                TcgVariant("normal", stamp = listOf("set-logo"), variantId = "stamped"),
                TcgVariant("normal", size = "jumbo", variantId = "jumbo"),
            ),
            cardLevel = 277142,
        )
        val byId = c.printings("en").associateBy { it.variantId }
        assertEquals(277142, byId["plain"]?.cardmarketId)
        assertEquals(0.25, byId["plain"]?.fallbackPrice)
        // Stamped and jumbo prints are other Cardmarket products.
        assertNull(byId["stamped"]?.cardmarketId)
        assertNull(byId["jumbo"]?.cardmarketId)
    }

    @Test
    fun catalogFixesReplaceTheLinkAndItsOldPrice() {
        val c = card(listOf(TcgVariant("normal", variantId = "plain")), cardLevel = 274152, fixes = mapOf("plain" to CardmarketFix(274212)))
        val p = c.printings("en").single()
        assertEquals(274212, p.cardmarketId)
        assertNull(p.fallbackPrice)
    }
}
