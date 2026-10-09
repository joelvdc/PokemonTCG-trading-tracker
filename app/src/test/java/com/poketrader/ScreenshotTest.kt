package com.poketrader

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.poketrader.data.Binder
import com.poketrader.data.CardRef
import com.poketrader.data.CollectionItem
import com.poketrader.data.CollectionRow
import com.poketrader.data.CollectionStats
import com.poketrader.data.Era
import com.poketrader.data.PriceType
import com.poketrader.data.StatMode
import com.poketrader.ui.PokeColors
import com.poketrader.ui.StatsContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar

/**
 * Screens drawn on the computer with sample cards, compared with the pictures in app/src/test/screenshots.
 *   gradlew recordRoborazziDebug   – (re)draws the reference pictures after an intended change
 *   gradlew verifyRoborazziDebug   – fails when a screen looks different from its picture
 * Plain `testDebugUnitTest` runs these without comparing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h3900dp-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val now = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 9, 12, 0) }.timeInMillis

    private fun monthsAgo(n: Int) = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, -n) }.timeInMillis

    private fun row(
        name: String, set: String, setName: String, number: String, official: Int?, rarity: String, price: Double?,
        variant: String = "Normal", lang: String = "en", qty: Int = 1, binder: Long = Binder.UNSORTED, paid: Double? = null,
        language: String = if (lang == "ja") "JA" else "EN", condition: String = "NM", added: Int = 0,
    ) = CollectionRow(
        CollectionItem(
            card = CardRef(
                cardId = "$set-$number", dataLang = lang, name = name, setId = set, setName = setName, localId = number,
                setOfficial = official, rarity = rarity, imageBase = null, variantId = variant.lowercase(), variantLabel = variant,
                cardmarketId = null, holoPrice = false, fallbackPrice = price, firstEdition = false,
            ),
            quantity = qty, binderId = binder, purchasePrice = paid, language = language, condition = condition, addedAt = monthsAgo(added),
        ),
        null,
    )

    private val rows = listOf(
        row("Charizard", "base1", "Base Set", "4", 102, "Rare Holo", 320.0, variant = "Holo", binder = 1, paid = 250.0, condition = "LP", added = 11),
        row("Pikachu", "base1", "Base Set", "58", 102, "Common", 4.5, qty = 3, added = 11),
        row("Bulbasaur", "base1", "Base Set", "44", 102, "Common", 2.0, qty = 2, added = 10),
        row("Pikachu ex", "sv08", "Surging Sparks", "57", 191, "Double rare", 6.2, variant = "Holo", added = 6),
        row("Pikachu ex", "sv08", "Surging Sparks", "238", 191, "Special illustration rare", 145.0, variant = "Holo", binder = 1, paid = 120.0, added = 6),
        row("Pikachu", "sv08", "Surging Sparks", "62", 191, "Common", 0.1, variant = "Reverse Holo", qty = 4, added = 5),
        row("Latios", "sv08", "Surging Sparks", "76", 191, "Rare", 0.3, added = 5),
        row("Fire Energy", "sve", "Scarlet & Violet Energy", "2", 8, "Common", 0.05, qty = 12, added = 4),
        row("Mega Lucario ex", "me01", "Mega Evolution", "77", 132, "Double rare", 3.8, variant = "Holo", added = 2),
        row("Mega Gardevoir ex", "me01", "Mega Evolution", "178", 132, "Mega Hyper Rare", 210.0, variant = "Holo", binder = 1, added = 2),
        row("Ralts", "me01", "Mega Evolution", "58", 132, "Common", 0.08, qty = 3, added = 1),
        row("Mewtwo V", "pgo", "Pokémon GO", "30", 78, "Ultra Rare", 4.0, variant = "Holo", added = 9),
        row("Radiant Charizard", "pgo", "Pokémon GO", "11", 78, "Radiant Rare", 12.0, variant = "Holo", added = 9),
        row("メガルカリオex", "M1L", "メガブレイブ", "15", 63, "Double rare", 2.5, variant = "Holo", lang = "ja", added = 1),
        row("ピカチュウ", "M1L", "メガブレイブ", "20", 63, "Common", null, lang = "ja", added = 0),
    )

    private val eras = mapOf(
        "en/base1" to Era("Base", 0), "en/pgo" to Era("Sword & Shield", 18), "en/sv08" to Era("Scarlet & Violet", 19),
        "en/sve" to Era("Scarlet & Violet", 19), "en/me01" to Era("Mega Evolution", 21), "ja/M1L" to Era("ポケモンカードゲーム MEGA", 22),
    )

    private fun stats() = CollectionStats.compute(rows, PriceType.TREND, mapOf(1L to "Best cards"), eras, binder = null, now = now)

    private fun shootStats(dark: Boolean, mode: StatMode, file: String) {
        compose.setContent {
            PokeColors(dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StatsContent(
                        stats(), PriceType.TREND, listOf(Binder(id = 1, name = "Best cards")), null, {}, mode, {},
                        erasLoaded = true, onPick = {}, onOpen = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$file.png")
    }

    @Test fun statsByCards() = shootStats(dark = false, mode = StatMode.CARDS, file = "stats_cards_light")

    @Test fun statsByValueDark() = shootStats(dark = true, mode = StatMode.VALUE, file = "stats_value_dark")
}
