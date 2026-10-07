package com.poketrader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.poketrader.scan.CardRecognizer
import com.poketrader.scan.CardTextParser
import com.poketrader.scan.OcrLine
import com.poketrader.scan.ScanGuide
import com.poketrader.scan.ScanResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real OCR → parser → TCGdex identification on card images drawn into a camera-sized
 * frame roughly where a user would hold the card (a bit smaller than the guide).
 */
@RunWith(AndroidJUnit4::class)
class ScanPipelineTest {
    private val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PokeApp

    private fun scan(asset: String, inset: Float = 0.04f): ScanResult? {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val card = ctx.assets.open(asset).use { BitmapFactory.decodeStream(it) }
        val frame = Bitmap.createBitmap(1440, 1920, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        canvas.drawColor(Color.rgb(60, 50, 45))
        val g = ScanGuide.boxFor(1440f, 1920f)
        val dx = g.width * inset
        val dy = g.height * inset
        canvas.drawBitmap(card, null, RectF(g.left + dx, g.top + dy, g.right - dx, g.bottom - dy), Paint(Paint.FILTER_BITMAP_FLAG))

        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(frame, 0)))
        val lines = text.textBlocks.flatMap { b ->
            b.lines.mapNotNull { l -> l.boundingBox?.let { OcrLine(l.text, it.left, it.top, it.right, it.bottom) } }
        }
        lines.filter { it.cy < g.top + g.height * 0.17f || it.cy > g.top + g.height * 0.82f }.forEach {
            Log.i("ScanTest", "  $asset line @(${((it.cx - g.left) / g.width * 100).toInt()}%,${((it.cy - g.top) / g.height * 100).toInt()}%) h=${it.height}: ${it.text}")
        }
        val clues = CardTextParser.parse(lines, g)
        val rec = CardRecognizer(app.container.tcgdex, app.container.sets)
        val result = runBlocking { rec.identify(clues) }
        val summary = when (result) {
            is ScanResult.Found -> "FOUND ${result.card.id} (${result.card.name}) lang=${result.language} exact=${result.exact} " +
                result.card.printings(result.dataLang).first().let { "cm=${it.cardmarketId} €${it.fallbackPrice} img=${it.thumbUrl}" }
            is ScanResult.Choose -> "CHOOSE ${result.name}: ${result.candidates.size} candidates, missing=${result.missing}"
            null -> "nothing (missing=${rec.lastMissing})"
        }
        Log.i("ScanTest", "$asset -> $clues -> $summary")
        return result
    }

    private fun assertFound(result: ScanResult?, id: String, language: String? = null) {
        assertTrue("expected $id but got $result", result is ScanResult.Found)
        result as ScanResult.Found
        assertEquals(id, result.card.id)
        if (language != null) assertEquals(language, result.language)
    }

    @Test fun modernEnglish() = assertFound(scan("en_modern.png"), "sv03.5-025", "EN")

    @Test fun swordShieldEnglish() = assertFound(scan("en_swsh.png"), "swsh3-136")

    @Test fun baseSetCharizard() = assertFound(scan("en_base.png"), "base1-4")

    @Test fun modernJapanese() = assertFound(scan("ja_modern.png"), "SV2a-025", "JA")

    @Test fun swordShieldFrench() = assertFound(scan("fr_swsh.png"), "swsh3-136", "FR")

    @Test fun modernFrench() = assertFound(scan("fr_modern.png"), "sv03.5-025", "FR")

    @Test fun cardHeldSmallerThanGuide() = assertFound(scan("en_modern.png", inset = 0.12f), "sv03.5-025")

    // Photos of real cards in sleeves (1.12). None of these four printings is in TCGdex (October 2026).
    // The Japanese and Korean ones come from Limitless TCG instead; TCG Classic (CLV) isn't anywhere,
    // so the scanner must say so instead of adding another card.

    @Test fun exFromRuleBox() {
        // The "ex" logo isn't read, the rule box is: offer Lugia ex cards, not plain Lugia.
        val r = scan("photo_en_lugia_ex_clv.jpg", inset = 0f)
        assertTrue("$r", r is ScanResult.Choose && r.candidates.isNotEmpty() && r.candidates.all { it.brief.name.lowercase() == "lugia ex" })
        assertEquals("CLV 017", (r as ScanResult.Choose).missing)
    }

    @Test fun koreanCardFromLimitless() = assertFound(scan("photo_ko_vaporeon_v.jpg", inset = 0f), "S6a-015", "KO")

    @Test fun japaneseCardFromLimitless() = assertFound(scan("photo_ja_tyranitar_go.jpg", inset = 0f), "S10b-043", "JA")

    @Test fun misreadJapaneseSetCode() = assertFound(scan("photo_ja_ditto_sv4a.jpg", inset = 0f), "SV4a-144", "JA")
}
