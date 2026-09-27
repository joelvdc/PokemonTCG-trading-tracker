package com.poketrader

import com.poketrader.data.Balance
import com.poketrader.data.PriceEntity
import com.poketrader.data.PriceSet
import com.poketrader.data.PriceType
import com.poketrader.data.Side
import com.poketrader.data.TcgCard
import com.poketrader.data.TradeItem
import com.poketrader.data.Verdict
import com.poketrader.scan.Box
import com.poketrader.scan.CardTextParser
import com.poketrader.scan.OcrLine
import com.poketrader.scan.ScanGuide
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTextParserTest {
    private val guide: Box = ScanGuide.boxFor(960f, 1280f)

    /** Places a line at fractional card coordinates (0..1) inside the guide. */
    private fun line(text: String, fx: Float, fy: Float, fw: Float = 0.4f, fh: Float = 0.03f): OcrLine {
        val l = guide.left + guide.width * fx
        val t = guide.top + guide.height * fy
        return OcrLine(text, l.toInt(), t.toInt(), (l + guide.width * fw).toInt(), (t + guide.height * fh).toInt())
    }

    @Test
    fun modernInternationalCard() {
        val clues = CardTextParser.parse(
            listOf(
                line("BASIC", 0.05f, 0.03f, 0.12f, 0.02f),
                line("Pikachu", 0.2f, 0.035f, 0.3f, 0.045f),
                line("HP 60", 0.7f, 0.035f, 0.2f, 0.035f),
                line("Illus. Kouki Saitou", 0.05f, 0.88f, 0.3f, 0.015f),
                line("G SVI EN 025/198 ●", 0.05f, 0.94f, 0.4f, 0.018f),
            ),
            guide,
        )
        assertEquals("Pikachu", clues.name)
        assertEquals("025", clues.number)
        assertEquals(198, clues.total)
        assertEquals("SVI", clues.setCode)
        assertEquals("EN", clues.language)
        assertFalse(clues.japanese)
    }

    @Test
    fun oldCardNumberBottomRightAndOcrZeros() {
        val clues = CardTextParser.parse(
            listOf(
                line("Charizard", 0.15f, 0.04f, 0.4f, 0.045f),
                line("120 HP", 0.7f, 0.04f, 0.2f, 0.035f),
                line("Illus. Mitsuhiro Arita", 0.05f, 0.9f, 0.3f, 0.015f),
                line("4/1O2 ★", 0.8f, 0.93f, 0.12f, 0.018f),
            ),
            guide,
        )
        assertEquals("Charizard", clues.name)
        assertEquals("4", clues.number)
        assertEquals(102, clues.total)
        assertNull(clues.setCode)
    }

    @Test
    fun japaneseCard() {
        val clues = CardTextParser.parse(
            listOf(
                line("たね", 0.05f, 0.03f, 0.08f, 0.02f),
                line("ピカチュウ", 0.18f, 0.035f, 0.3f, 0.045f),
                line("HP60", 0.72f, 0.035f, 0.15f, 0.035f),
                line("SV2a 025/165 C", 0.05f, 0.94f, 0.3f, 0.018f),
            ),
            guide,
        )
        assertTrue(clues.japanese)
        assertEquals("ピカチュウ", clues.name)
        assertEquals("025", clues.number)
        assertEquals(165, clues.total)
        assertEquals("SV2a", clues.setCode)
        assertEquals("JA", clues.language)
    }

    @Test
    fun galleryNumbersAndVCards() {
        val clues = CardTextParser.parse(
            listOf(
                line("Pikachu V", 0.2f, 0.035f, 0.3f, 0.045f),
                line("TG16/TG30", 0.05f, 0.94f, 0.2f, 0.018f),
            ),
            guide,
        )
        assertEquals("Pikachu V", clues.name)
        assertEquals("TG16", clues.number)
        assertEquals(30, clues.total)
    }

    /** Lines as ML Kit read them off a Sword & Shield card on the emulator. */
    @Test
    fun realOcrSwordShieldRegulationMarkAndFrenchLabels() {
        val clues = CardTextParser.parse(
            listOf(
                line("NIVEAU l Fouinar", 0.1f, 0.07f, 0.3f, 0.045f),
                line("Évolution de:Fouinette", 0.2f, 0.12f, 0.25f, 0.02f),
                line("Faiblesse x2 Résistance", 0.1f, 0.83f, 0.3f, 0.02f),
                line("けD136/189◆", 0.1f, 0.9f, 0.2f, 0.03f),
                line("vl10", 0.75f, 0.07f, 0.1f, 0.04f),
            ),
            guide,
        )
        assertFalse(clues.japanese)
        assertEquals("Fouinar", clues.name)
        assertEquals("136", clues.number)
        assertEquals(189, clues.total)
        assertEquals("FR", clues.language)
    }

    /** Flavour text ("When it…") must not be read as set code + language; stray kanji isn't Japanese. */
    @Test
    fun realOcrModernEnglish() {
        val clues = CardTextParser.parse(
            listOf(
                line("日ASIG", 0.12f, 0.13f, 0.12f, 0.02f),
                line("Pikachu", 0.25f, 0.13f, 0.2f, 0.04f),
                line("weakness 明×2", 0.08f, 0.83f, 0.2f, 0.02f),
                line("G MEWa", 0.08f, 0.9f, 0.1f, 0.03f),
                line("025/165", 0.2f, 0.91f, 0.1f, 0.02f),
                line("When it is angered, it immediately discharges the", 0.45f, 0.88f, 0.5f, 0.02f),
            ),
            guide,
        )
        assertFalse(clues.japanese)
        assertEquals("Pikachu", clues.name)
        assertEquals("025", clues.number)
        assertNull(clues.setCode)
        assertEquals("EN", clues.language)
    }

    @Test
    fun similarityToleratesOcrErrors() {
        assertTrue(CardTextParser.similarity("Pikachv", "Pikachu") >= 0.8)
        assertTrue(CardTextParser.similarity("Charizard", "Pikachu") < 0.4)
        assertEquals(1.0, CardTextParser.similarity("Mr. Mime", "Mr Mime"), 1e-9)
    }
}

class VariantTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    // Shapes as returned by api.tcgdex.net (trimmed).
    private val pikachu = """
        {"id":"SV2a-025","localId":"025","name":"ピカチュウ","image":"https://assets.tcgdex.net/ja/SV/SV2a/025","rarity":"Common",
         "set":{"id":"SV2a","name":"ポケモンカード151","cardCount":{"official":165,"total":210}},
         "variants_detailed":[
          {"type":"normal","size":"standard","thirdParty":{"cardmarket":719467},"variantId":"n1","pricing":{"cardmarket":{"idProduct":719467,"trend":0.12,"trend-holo":3.01}}},
          {"type":"reverse","size":"standard","foil":"pokeball","thirdParty":{"cardmarket":837271},"variantId":"r1","pricing":{"cardmarket":{"idProduct":837271,"trend":0.28,"trend-holo":1.35}}},
          {"type":"reverse","size":"standard","foil":"masterball","thirdParty":{"cardmarket":837272},"variantId":"r2","pricing":{"cardmarket":{"idProduct":837272,"trend":0,"trend-holo":457.27}}},
          {"type":"normal","size":"jumbo","variantId":"j1"}
         ]}
    """.trimIndent()

    private val charizard = """
        {"id":"base1-4","localId":"4","name":"Charizard","rarity":"Rare","set":{"id":"base1","name":"Base Set","cardCount":{"official":102,"total":102}},
         "variants_detailed":[
          {"type":"holo","subtype":"unlimited","size":"standard","thirdParty":{"cardmarket":273699},"variantId":"h1"},
          {"type":"holo","subtype":"shadowless","size":"standard","stamp":["1st-edition"],"thirdParty":{"cardmarket":660224},"variantId":"h2"}
         ]}
    """.trimIndent()

    @Test
    fun printingsNamesAndPriceColumns() {
        val card = json.decodeFromString<TcgCard>(pikachu)
        val p = card.printings("ja")
        assertEquals(listOf("Normal", "Reverse Holo · Poké Ball", "Reverse Holo · Master Ball"), p.map { it.variantLabel })
        assertEquals(listOf(false, true, true), p.map { it.holoPrice })
        assertEquals(837272, p[2].cardmarketId)
        assertEquals(457.27, p[2].fallbackPrice!!, 1e-9)
        assertEquals(0.12, p[0].fallbackPrice!!, 1e-9)
        assertEquals("025/165", p[0].numberLabel)
        assertEquals("https://assets.tcgdex.net/ja/SV/SV2a/025/low.webp", p[0].thumbUrl)
    }

    @Test
    fun defaultPrintingAndFirstEdition() {
        val card = json.decodeFromString<TcgCard>(charizard)
        val p = card.printings("en")
        assertEquals(listOf("Holo", "Holo · Shadowless · 1st Edition"), p.map { it.variantLabel })
        assertTrue(p[1].firstEdition)
        assertEquals("h1", card.defaultPrinting("en", preferHolo = false).variantId)
        val pika = json.decodeFromString<TcgCard>(pikachu)
        assertEquals("n1", pika.defaultPrinting("ja", preferHolo = false).variantId)
        assertEquals("r1", pika.defaultPrinting("ja", preferHolo = true).variantId)
    }

    @Test
    fun priceGuideColumnFallback() {
        val e = PriceEntity(1, null, null, null, null, null, null, 2.0, 1.0, 3.0, null, null, null)
        assertEquals(3.0, e.toSet(holo = true).trend!!, 1e-9)
        // No normal-copy prices recorded: fall back to the holo column rather than showing nothing.
        assertEquals(3.0, e.toSet(holo = false).trend!!, 1e-9)
    }
}

class BalanceTest {
    private val card = Json { ignoreUnknownKeys = true }.decodeFromString<TcgCard>(
        """{"id":"x-1","localId":"1","name":"X","set":{"id":"x"}}"""
    ).printings("en").first()

    private fun item(side: String, price: Double, qty: Int = 1, custom: Double? = null) =
        TradeItem(tradeId = 1, side = side, card = card, quantity = qty, prices = PriceSet(trend = price), customPrice = custom)

    @Test
    fun verdicts() {
        assertEquals(Verdict.FAIR, Balance.of(listOf(item(Side.GIVE, 10.0), item(Side.GET, 10.4)), PriceType.TREND, 5).verdict)
        assertEquals(Verdict.FAVORS_THEM, Balance.of(listOf(item(Side.GIVE, 10.0, 2), item(Side.GET, 12.0)), PriceType.TREND, 5).verdict)
        assertEquals(Verdict.FAVORS_YOU, Balance.of(listOf(item(Side.GIVE, 1.0), item(Side.GET, 5.0, custom = 9.0)), PriceType.TREND, 5).verdict)
    }
}
