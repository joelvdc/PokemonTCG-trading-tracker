package com.poketrader

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.poketrader.data.AppDatabase
import com.poketrader.data.CardRef
import com.poketrader.data.CollectionItem
import com.poketrader.data.CollectionRow
import com.poketrader.data.PriceSource
import com.poketrader.data.PriceType
import com.poketrader.data.Pricing
import com.poketrader.data.SourcePrice
import com.poketrader.data.TcgCard
import com.poketrader.data.ValueHistory
import com.poketrader.data.tcgplayerPrices
import com.poketrader.ui.priceGaps
import com.poketrader.ui.sourceTotals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** 1.16: TCGplayer prices. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PriceSourceTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private fun card(id: String) = json.decodeFromString(TcgCard.serializer(), javaClass.getResource("/tcgdex/$id.json")!!.readText())

    @After
    fun reset() {
        Pricing.source = PriceSource.CARDMARKET
        Pricing.tcgplayer = emptyMap()
        Pricing.usdPerEuro = null
    }

    @Test
    fun matchesTcgplayersPricesToVersions() {
        // Bulbasaur (Scarlet & Violet): normal and reverse holo, each with its own price.
        val sv = tcgplayerPrices(card("sv01-001"))
        val variants = card("sv01-001").printings("en").associate { it.variantId to it.variantLabel }
        val byLabel = sv.mapKeys { variants[it.key] }
        assertEquals(setOf("Normal", "Reverse Holo"), byLabel.keys)
        assertTrue(byLabel.values.all { it.price!! > 0 && it.url!!.startsWith("https://www.tcgplayer.com/product/") })
        assertTrue(byLabel.getValue("Reverse Holo").price!! > byLabel.getValue("Normal").price!!)

        // Base Set Charizard: TCGplayer's one holo price is the unlimited print's; the 1st edition and shadowless prints get none.
        val base = card("base1-4")
        val prices = tcgplayerPrices(base)
        val versions = base.tcgdexLinks().map { it.first }
        assertEquals(1, prices.size)
        assertEquals("unlimited", versions.single { it.variantId == prices.keys.single() }.subtype)
    }

    private fun ref(id: String, variant: String = "normal", lang: String = "en", cardmarket: Double = 1.0) =
        CardRef(id, lang, "Card $id", "sv01", "Scarlet & Violet", "1", 198, "Common", null, variant, "Normal", null, false, cardmarket, false)

    private fun row(card: CardRef, qty: Int) = CollectionRow(CollectionItem(card = card, quantity = qty), null)

    @Test
    fun pricesFollowTheChosenSource() {
        val a = ref("a")
        val jp = ref("b", lang = "ja", cardmarket = 4.0)
        Pricing.tcgplayer = mapOf(Pricing.key(a) to SourcePrice(Pricing.key(a), "tcgplayer", 2.5))
        Pricing.usdPerEuro = 1.25
        assertEquals(1.0, row(a, 1).unitPrice(PriceType.TREND)!!, 1e-9)
        Pricing.source = PriceSource.TCGPLAYER
        assertEquals(2.0, row(a, 1).unitPrice(PriceType.TREND)!!, 1e-9)
        // No TCGplayer price: Cardmarket's, marked.
        assertEquals(4.0, row(jp, 1).unitPrice(PriceType.TREND)!!, 1e-9)
        assertTrue(row(jp, 1).isApprox)
        assertNull(row(a, 1).trend)
        // Not before the exchange rate is known.
        Pricing.usdPerEuro = null
        assertTrue(row(a, 1).isApprox)
    }

    @Test
    fun historyComparisonAndGaps() {
        val a = ref("a")
        val b = ref("b", cardmarket = 4.0)
        val rows = listOf(row(a, 3), row(b, 1))
        assertEquals(listOf("trend"), ValueHistory.snapshotValues(rows, emptyList()).keys.filter { it.startsWith("trend") })
        Pricing.tcgplayer = mapOf(Pricing.key(a) to SourcePrice(Pricing.key(a), "tcgplayer", 2.5))
        Pricing.usdPerEuro = 1.25
        val v = ValueHistory.snapshotValues(rows, emptyList())
        assertEquals(7.0, v["trend"]!!, 1e-9)
        assertEquals(10.0, v["trend@tcgplayer"]!!, 1e-9) // 3 × 2 + Cardmarket's 4
        assertEquals(listOf(4, 3), sourceTotals(rows, PriceType.TREND).map { it.covered })
        assertEquals(3.0, priceGaps(rows, PriceType.TREND).single().difference, 1e-9)
    }

    @Test
    fun updatingFromVersion4AddsThePriceTable() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = context.getDatabasePath("update-5.db").apply { parentFile?.mkdirs(); delete() }
        val schema = Json.parseToJsonElement(File("schemas/com.poketrader.data.AppDatabase/4.json").readText()).jsonObject.getValue("database").jsonObject
        val sql = SQLiteDatabase.openOrCreateDatabase(file, null)
        for (e in schema.getValue("entities").jsonArray) {
            val table = e.jsonObject.getValue("tableName").jsonPrimitive.content
            sql.execSQL(e.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            e.jsonObject["indices"]?.jsonArray?.forEach { i -> sql.execSQL(i.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        }
        for (q in schema.getValue("setupQueries").jsonArray) sql.execSQL(q.jsonPrimitive.content)
        sql.version = 4
        sql.close()
        // Room checks the result against what this version expects.
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "update-5.db").addMigrations(AppDatabase.MIGRATION_4_5).build()
        try {
            db.sourcePriceDao().putAll(listOf(SourcePrice("a|en|normal", "tcgplayer", 2.5)))
            assertEquals(1, db.sourcePriceDao().forKeys(listOf("a|en|normal")).size)
        } finally {
            db.close()
        }
    }
}
