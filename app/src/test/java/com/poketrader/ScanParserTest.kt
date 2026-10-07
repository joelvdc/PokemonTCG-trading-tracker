package com.poketrader

import com.poketrader.scan.Box
import com.poketrader.scan.CardTextParser
import com.poketrader.scan.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parser on what ML Kit actually read off photos of real cards (see ScanPipelineTest.photosProbe). */
class ScanParserTest {
    private val guide = Box(0f, 0f, 1000f, 1400f)

    /** A line centred at ([x]%, [y]%) of the card, [h] pixels high. */
    private fun line(text: String, x: Int, y: Int, h: Int = 40): OcrLine {
        val cx = x * 10
        val cy = y * 14
        val w = text.length * h / 2
        return OcrLine(text, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    @Test fun exFromTheRuleBox() {
        // Lugia ex from TCG Classic: the "ex" logo was read as "@", the rule box says it's an ex.
        val c = CardTextParser.parse(
            listOf(
                line("BASIS", 13, 3, 31), line("「Lugia@", 34, 5, 67), line("230*", 77, 6, 63),
                line("weakness", 10, 85, 26), line("ケ×2|resistance -30", 35, 84, 56), line("retreat", 67, 83, 23),
                line("Pokémon exrule", 53, 87, 47), line("When your Pokémon ex", 83, 88, 42),
                line("Illus. PLANETA Hirogi", 17, 90, 37), line("CLV 017/034", 19, 94, 42),
            ),
            guide,
        )
        assertEquals("Lugia ex", c.name)
        assertEquals("017", c.number)
        assertEquals(34, c.total)
        assertEquals("CLV", c.setCode)
        assertEquals("EN", c.language)
        assertFalse(c.asian)
        assertEquals("en", c.dataLang)
    }

    @Test fun nameAlreadyWithSuffixIsKept() {
        val c = CardTextParser.parse(listOf(line("Pikachu ex", 30, 5, 60), line("Pokémon ex rule", 53, 87), line("SVI EN 063/198", 20, 94)), guide)
        assertEquals("Pikachu ex", c.name)
    }

    @Test fun vFromTheRuleBox() {
        val c = CardTextParser.parse(listOf(line("Vaporeon", 30, 5, 60), line("V rule", 20, 88), line("When your Pokémon V is Knocked Out", 60, 89)), guide)
        assertEquals("Vaporeon V", c.name)
    }

    @Test fun koreanPrintIsLookedUpInTheJapaneseSets() {
        // Korean Vaporeon V (Eevee Heroes): no Hangul read at all, "S6a" read as "Sba", regulation mark E.
        val c = CardTextParser.parse(
            listOf(line("-210", 79, 15, 86), line("都×2", 21, 85), line("lilus. Sban Grophics", 21, 89, 35), line("Sba E 015/069 RR", 23, 92, 48)),
            guide,
        )
        assertNull(c.name)
        assertEquals("015", c.number)
        assertEquals(69, c.total)
        assertEquals("Sba", c.setCode)
        assertTrue(c.asian)
        assertEquals("KO", c.language)
        assertEquals("ja", c.dataLang)
    }

    @Test fun japaneseSetCodeWithRegulationMarkGlued() {
        // Ditto from Shiny Treasure ex: "G SV4a" read as "GS4s".
        val c = CardTextParser.parse(
            listOf(line("たね", 11, 11, 35), line("メタモン", 28, 12, 65), line("60本", 75, 9, 69), line("GS4s 144/190", 19, 92)),
            guide,
        )
        assertEquals("メタモン", c.name)
        assertEquals("144", c.number)
        assertEquals("GS4s", c.setCode)
        assertEquals("JA", c.language)
        assertTrue(CardTextParser.codeSimilarity("GS4s", "SV4a") >= 0.5)
        // Shining Star V (S4a) has 190 cards too; the glued "G" mark says Scarlet & Violet.
        assertTrue(CardTextParser.codeSimilarity("GS4s", "SV4a") > CardTextParser.codeSimilarity("GS4s", "S4a"))
        assertTrue(CardTextParser.codeSimilarity("Sba", "S6a") >= 0.5)
        assertTrue(CardTextParser.codeSimilarity("Sba", "S6a") > CardTextParser.codeSimilarity("Sba", "S8a"))
        assertEquals(1.0, CardTextParser.codeSimilarity("S10b", "S10b"), 0.0)
    }

    @Test fun japaneseExGetsTheSuffixWithoutASpace() {
        val c = CardTextParser.parse(listOf(line("たね", 11, 4, 35), line("メタモン", 28, 5, 65), line("ポケモンexがきぜつしたとき", 60, 88), line("SV4a 144/190", 19, 93)), guide)
        assertEquals("メタモンex", c.name)
    }

    @Test fun internationalCardsAreNotTakenForAsianOnes() {
        // Modern English: "SVI EN" names the language; an "SV" code without digits isn't Japanese-style.
        val c = CardTextParser.parse(listOf(line("Pikachu", 30, 5, 60), line("weakness", 10, 85), line("SVI EN 025/198", 20, 94)), guide)
        assertFalse(c.asian)
        assertEquals("SVI", c.setCode)
        assertEquals("EN", c.language)
    }
}
