package com.poketrader

import com.poketrader.data.Binder
import com.poketrader.data.CardRef
import com.poketrader.data.CollectionFilter
import com.poketrader.data.CollectionItem
import com.poketrader.data.CollectionRow
import com.poketrader.data.CollectionStats
import com.poketrader.data.Era
import com.poketrader.data.PriceType
import com.poketrader.data.PrintFilter
import com.poketrader.data.SetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CollectionStatsTest {
    private fun row(
        name: String, set: String = "Base", number: String = "1", official: Int? = 102, rarity: String = "Common", price: Double? = null,
        variant: String = "Normal", lang: String = "en", qty: Int = 1, binder: Long = Binder.UNSORTED, paid: Double? = null, addedAt: Long = 0,
    ) = CollectionRow(
        CollectionItem(
            card = CardRef(
                cardId = "$set-$number", dataLang = lang, name = name, setId = set.lowercase(), setName = set, localId = number,
                setOfficial = official, rarity = rarity, imageBase = null, variantId = variant.lowercase(), variantLabel = variant,
                cardmarketId = null, holoPrice = false, fallbackPrice = price, firstEdition = false,
            ),
            quantity = qty, binderId = binder, purchasePrice = paid, addedAt = addedAt,
        ),
        null,
    )

    private fun stats(rows: List<CollectionRow>, eras: Map<String, Era> = emptyMap(), now: Long = System.currentTimeMillis()) =
        CollectionStats.compute(rows, PriceType.TREND, mapOf(5L to "Trade binder"), eras, binder = null, now = now)

    @Test fun readsTheKindOfPokemonFromTheName() {
        assertEquals("Pokémon ex", CollectionStats.kind("Pikachu ex"))
        assertEquals("Mega Pokémon ex", CollectionStats.kind("Mega Lucario ex"))
        assertEquals("Pokémon-EX", CollectionStats.kind("Charizard-EX"))
        assertEquals("Mega Pokémon-EX", CollectionStats.kind("M Charizard EX"))
        assertEquals("Pokémon VMAX", CollectionStats.kind("Pikachu VMAX"))
        assertEquals("Pokémon ex", CollectionStats.kind("メタモンex"))
        assertEquals("Radiant Pokémon", CollectionStats.kind("Radiant Greninja"))
        assertEquals("Prism Star", CollectionStats.kind("Lugia ◇"))
        assertNull(CollectionStats.kind("Exeggcute"))
        assertNull(CollectionStats.kind("Professor's Research"))
        assertEquals("Lucario", CollectionStats.baseName("Mega Lucario ex"))
        assertEquals("Pikachu", CollectionStats.baseName("Pikachu V"))
        assertEquals("Charizard", CollectionStats.baseName("Charizard-GX"))
        assertEquals("Exeggcute", CollectionStats.baseName("Exeggcute"))
    }

    @Test fun countsTotalsRaritiesAndPokemon() {
        val s = stats(
            listOf(
                row("Pikachu", number = "58", price = 2.0, qty = 3),
                row("Pikachu ex", set = "Surging Sparks", number = "57", official = 191, rarity = "Double rare", price = 5.0),
                row("Charizard", number = "4", rarity = "Rare Holo", price = 300.0, paid = 200.0),
                row("Fire Energy", number = "98", price = null, qty = 10),
            ),
        )
        assertEquals(15, s.copies)
        assertEquals(4, s.uniqueNames)
        assertEquals(10, s.unpriced)
        assertEquals(311.0, s.value, 1e-9)
        assertEquals(1, s.paidCopies)
        assertEquals(200.0, s.paid, 1e-9)
        assertEquals(300.0, s.paidNowWorth, 1e-9)
        // Rarest first.
        assertEquals(listOf("Double rare", "Rare Holo", "Common"), s.rarities.map { it.key })
        assertEquals(CollectionFilter(rarities = setOf("Double rare")), s.rarities.first().jump?.filter)
        // Pikachu and Pikachu ex together; Energy left out.
        assertEquals("Pikachu", s.pokemon.first().label)
        assertEquals(4, s.pokemon.first().copies)
        assertEquals("Pikachu", s.pokemon.first().jump?.search)
        assertTrue(s.pokemon.none { "Energy" in it.label })
        assertEquals(listOf("Pokémon ex"), s.kinds.map { it.label })
        assertEquals(listOf(0, 3, 1, 0, 0, 1), s.priceRanges.map { it.copies })
        assertEquals(100.0, s.priceRanges.last().jump?.filter?.minPrice)
        assertEquals("Charizard", s.mostValuable.first().row.item.card.name)
    }

    @Test fun setCompletionCountsDifferentNumbers() {
        val s = stats(
            listOf(
                row("Bulbasaur", number = "44", official = 102), row("Bulbasaur", number = "44", official = 102, variant = "1st Edition"),
                row("Ivysaur", number = "30", official = 102),
                row("Mew ex", set = "151", number = "205", official = 165), row("Bulbasaur", set = "151", number = "1", official = 165),
                row("Promo", set = "Promos", number = "SWSH001", official = null),
            ),
        )
        val base = s.completion.first { it.key == "base" }
        assertEquals("2/102", base.detail)
        assertEquals(2.0 / 102, base.fraction!!, 1e-9)
        assertEquals("1/165 +1", s.completion.first { it.key == "151" }.detail)
        // A set without an official count isn't listed.
        assertTrue(s.completion.none { it.key == "promos" })
        assertEquals(CollectionFilter(sets = setOf("base")), base.jump?.filter)
    }

    @Test fun groupsByEraPrintAndBinder() {
        val eras = mapOf("en/base" to Era("Base", 0), "en/sv01" to Era("Scarlet & Violet", 20), "ja/m2a" to Era("MEGA", 22))
        val s = stats(
            listOf(
                row("Pikachu", set = "SV01"), row("Abra", set = "Base", binder = 5L), row("ピカチュウ", set = "M2a", lang = "ja"),
                row("Mystery", set = "Unknown"),
            ),
            eras,
        )
        assertEquals(listOf("Base", "Scarlet & Violet", "MEGA", "Other"), s.eras.map { it.label })
        assertEquals(listOf("International prints", "Japanese prints"), s.prints.map { it.label })
        assertEquals(CollectionFilter(print = PrintFilter.JAPANESE), s.prints.last().jump?.filter)
        assertEquals(setOf("Trade binder", Binder.UNSORTED_NAME), s.binders.map { it.label }.toSet())
        assertEquals(5L, s.binders.first { it.label == "Trade binder" }.jump?.binder)
    }

    @Test fun cardsAddedPerMonth() {
        val now = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 9, 12, 0) }.timeInMillis
        val sept = Calendar.getInstance().apply { clear(); set(2026, Calendar.SEPTEMBER, 20) }.timeInMillis
        val lastYear = Calendar.getInstance().apply { clear(); set(2025, Calendar.MARCH, 1) }.timeInMillis
        val s = stats(listOf(row("A", addedAt = now, qty = 2), row("B", addedAt = sept), row("C", addedAt = lastYear)), now = now)
        assertEquals(12, s.added.size)
        assertEquals(2, s.added.last().copies)
        assertEquals(1, s.added[10].copies)
        // Older than a year isn't shown.
        assertEquals(3, s.added.sumOf { it.copies })
    }

    @Test fun erasRoundTripThroughTheCacheFile() {
        val eras = mapOf("sv01" to Era("Scarlet & Violet", 20), "me01" to Era("Mega Evolution", 22))
        assertEquals(eras, SetCatalog.decodeEras(SetCatalog.encodeEras(eras)))
    }
}
