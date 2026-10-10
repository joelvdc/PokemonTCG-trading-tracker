package com.poketrader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.poketrader.data.CardRef
import com.poketrader.data.CollectionItem
import com.poketrader.data.CollectionRow
import com.poketrader.data.PriceSource
import com.poketrader.data.PriceSourceStore
import com.poketrader.data.PriceType
import com.poketrader.data.Pricing
import com.poketrader.data.SourcePrice
import com.poketrader.ui.OtherPrices
import com.poketrader.ui.PokeColors
import com.poketrader.ui.PriceGapList
import com.poketrader.ui.PriceSourceSection
import com.poketrader.ui.SourceComparison
import com.poketrader.ui.priceGaps
import com.poketrader.ui.sourceTotals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 1.16: the price sources compared, the biggest gaps, a card's TCGplayer price and the Settings choice. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xhdpi")
class PriceSourceScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun ref(name: String, set: String, variant: String, label: String, cardmarket: Double, lang: String = "en") =
        CardRef("$set-$name", lang, name, set, set, "4", 102, "Rare Holo", null, variant, label, null, false, cardmarket, false)

    @Test fun priceSources() {
        val charizard = ref("Charizard", "Base Set", "holo", "Holo", 345.0)
        val pikachu = ref("Pikachu ex", "Surging Sparks", "holo", "Holo", 41.5)
        val japanese = ref("ピカチュウ", "SV-P", "normal", "Normal", 6.0, lang = "ja")
        Pricing.usdPerEuro = 1.12
        Pricing.tcgplayer = listOf(charizard to 428.0, pikachu to 39.9).associate { (c, usd) ->
            Pricing.key(c) to SourcePrice(Pricing.key(c), "tcgplayer", usd, "https://www.tcgplayer.com/product/42382")
        }
        val rows = listOf(charizard to 1, pikachu to 2, japanese to 3).map { (c, n) -> CollectionRow(CollectionItem(card = c, quantity = n), null) }
        try {
            compose.setContent {
                PokeColors(false) {
                    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            SourceComparison(sourceTotals(rows, PriceType.TREND), PriceSource.CARDMARKET, ratesKnown = true, selected = PriceSource.TCGPLAYER) {}
                            PriceGapList(priceGaps(rows, PriceType.TREND))
                            OtherPrices(charizard) {}
                            OtherPrices(japanese) {}
                            PriceSourceSection(PriceSource.TCGPLAYER, PriceSourceStore.Status(1_760_000_000_000)) {}
                        }
                    }
                }
            }
            compose.onRoot().captureRoboImage("src/test/screenshots/price_sources.png")
        } finally {
            Pricing.usdPerEuro = null
            Pricing.tcgplayer = emptyMap()
        }
    }

    /** 1.17: TCGplayer's download bar above the tabs (running, then failed). */
    @Test fun sourceDownloadBar() {
        compose.setContent {
            PokeColors(false) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.poketrader.ui.SourceStatusStrip(PriceSourceStore.Status(running = true, done = 420, total = 1_150))
                    com.poketrader.ui.SourceStatusStrip(PriceSourceStore.Status(error = "12 of 1150 cards couldn't be read"))
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/source_download_bar.png")
    }
}
