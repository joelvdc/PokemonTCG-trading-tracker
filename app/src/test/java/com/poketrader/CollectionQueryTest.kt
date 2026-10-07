package com.poketrader

import com.poketrader.data.CardRef
import com.poketrader.data.CollectionFilter
import com.poketrader.data.CollectionItem
import com.poketrader.data.CollectionRow
import com.poketrader.data.PriceType
import com.poketrader.data.PrintFilter
import com.poketrader.data.Rarities
import com.poketrader.data.SortField
import com.poketrader.data.SortLevel
import com.poketrader.data.SortSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionQueryTest {
    private fun row(
        name: String, set: String = "Base", number: String = "1", rarity: String = "Common", price: Double? = null,
        variant: String = "Normal", lang: String = "en", condition: String = "NM", addedAt: Long = 0, id: Long = 0,
    ) = CollectionRow(
        CollectionItem(
            id = id,
            card = CardRef(
                cardId = "$set-$number", dataLang = lang, name = name, setId = set.lowercase(), setName = set, localId = number,
                setOfficial = null, rarity = rarity, imageBase = null, variantId = variant.lowercase(), variantLabel = variant,
                cardmarketId = null, holoPrice = false, fallbackPrice = price, firstEdition = false,
            ),
            condition = condition, quantity = 1, addedAt = addedAt,
        ),
        null,
    )

    @Test fun sortsInLayers() {
        val rows = listOf(row("Pikachu", "Jungle", "60"), row("Abra", "Base", "43"), row("Pikachu", "Base", "58"), row("Pikachu", "Base", "9"))
        val bySetNumber = rows.sortedWith(SortSpec(listOf(SortLevel(SortField.NUMBER))).comparator(PriceType.TREND))
        assertEquals(listOf("9", "43", "58", "60"), bySetNumber.map { it.item.card.localId })
        val byNameThenSet = rows.sortedWith(SortSpec(listOf(SortLevel(SortField.NAME), SortLevel(SortField.SET, reversed = true))).comparator(PriceType.TREND))
        assertEquals(listOf("Abra", "Pikachu", "Pikachu", "Pikachu"), byNameThenSet.map { it.item.card.name })
        assertEquals("Jungle", byNameThenSet[1].item.card.setName)
    }

    @Test fun valueAndRaritySortHighestFirst() {
        val rows = listOf(row("A", price = 1.0, rarity = "Common"), row("B", price = null, rarity = "Special illustration rare"), row("C", price = 9.0, rarity = "Rare Holo"))
        assertEquals(listOf("C", "A", "B"), rows.sortedWith(SortSpec(listOf(SortLevel(SortField.VALUE))).comparator(PriceType.TREND)).map { it.item.card.name })
        assertEquals(listOf("B", "C", "A"), rows.sortedWith(SortSpec(listOf(SortLevel(SortField.RARITY))).comparator(PriceType.TREND)).map { it.item.card.name })
    }

    @Test fun sortSpecRoundTrips() {
        val spec = SortSpec(listOf(SortLevel(SortField.RARITY), SortLevel(SortField.VALUE, true)))
        assertEquals(spec, SortSpec.decode(spec.encode()))
        assertEquals(SortSpec(), SortSpec.decode("garbage"))
        assertEquals(SortSpec(), SortSpec.decode(null))
    }

    @Test fun rarityRanks() {
        assertTrue(Rarities.rank("Hyper rare") > Rarities.rank("Special illustration rare"))
        assertTrue(Rarities.rank("Special illustration rare") > Rarities.rank("Illustration rare"))
        assertTrue(Rarities.rank("Double rare") > Rarities.rank("Rare Holo"))
        assertTrue(Rarities.rank("Rare Holo") > Rarities.rank("Rare"))
        assertTrue(Rarities.rank("Rare") > Rarities.rank("Uncommon"))
        assertTrue(Rarities.rank("Uncommon") > Rarities.rank("Common"))
    }

    @Test fun filterMatchesAllParts() {
        val holo = row("Charizard", "Base", "4", "Rare Holo", price = 300.0, variant = "Holo")
        val jp = row("Pikachu", "SV-P", "1", "Promo", price = 5.0, lang = "ja")
        val pt = PriceType.TREND
        assertTrue(CollectionFilter().matches(holo, pt))
        assertTrue(CollectionFilter(rarities = setOf("Rare Holo", "Rare")).matches(holo, pt))
        assertFalse(CollectionFilter(rarities = setOf("Rare")).matches(holo, pt))
        assertTrue(CollectionFilter(sets = setOf("base"), variants = setOf("Holo")).matches(holo, pt))
        assertFalse(CollectionFilter(sets = setOf("base"), variants = setOf("Reverse Holo")).matches(holo, pt))
        assertTrue(CollectionFilter(print = PrintFilter.JAPANESE).matches(jp, pt))
        assertFalse(CollectionFilter(print = PrintFilter.JAPANESE).matches(holo, pt))
        assertFalse(CollectionFilter(print = PrintFilter.INTERNATIONAL).matches(jp, pt))
        assertTrue(CollectionFilter(minPrice = 100.0).matches(holo, pt))
        assertFalse(CollectionFilter(maxPrice = 100.0).matches(holo, pt))
        assertFalse(CollectionFilter(minPrice = 1.0).matches(row("No price"), pt))
        assertFalse(CollectionFilter(conditions = setOf("LP")).matches(holo, pt))
    }

    @Test fun filterCountsAndRoundTrips() {
        val f = CollectionFilter(rarities = setOf("Rare"), minPrice = 1.0, maxPrice = 5.0, print = PrintFilter.JAPANESE)
        assertEquals(3, f.count)
        assertFalse(f.isEmpty)
        assertEquals(f, CollectionFilter.decode(f.encode()))
        assertTrue(CollectionFilter.decode("").isEmpty)
    }
}
