package com.poketrader.data

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** One international printing of a Japanese card, as Limitless lists it: "MEW", "Pokémon 151", "132", €0.77. */
data class EnglishPrint(val code: String, val setName: String, val number: String, val eur: Double?) {
    /** Promo sets are listed under codes; their TCGdex numbers carry a prefix ("SWSH150"). */
    val isPromo get() = code in PROMOS

    companion object {
        /** Limitless promo codes → TCGdex set id and number prefix. */
        val PROMOS = mapOf("SP" to ("swshp" to "SWSH"), "SVP" to ("svp" to ""), "SMP" to ("smp" to "SM"), "XYP" to ("xyp" to "XY"))
    }
}

/** What a Limitless TCG card page says about a Japanese card. */
data class LimitlessPage(
    val name: String,
    /** Picture URL without its size suffix; see [FallbackImages]. */
    val imageBase: String?,
    val setName: String?,
    val rarity: String?,
    /** The same card's international printings, in Limitless's order. */
    val english: List<EnglishPrint>,
)

/**
 * Japanese (and so Korean) cards TCGdex is missing: TCGdex lists many Japanese sets with no or only
 * some cards (Pokémon GO S10b, Eevee Heroes S6a, Shiny Treasure ex SV4a…). Limitless TCG
 * (limitlesstcg.com) has them, with a picture and the matching international printing, whose
 * Cardmarket product gives the price. Only asked for sets TCGdex knows, and only for cards it lacks.
 * Pages are cached on the phone. Since 1.12.
 */
class LimitlessCards(
    context: Context,
    private val http: OkHttpClient,
    private val tcgdex: TcgdexApi,
    private val catalog: SetCatalog,
) {
    private val dir = File(context.filesDir, "limitless").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val lock = Mutex()
    private val memory = HashMap<String, TcgCard?>()
    private val throttle = Mutex()
    private var lastCall = 0L

    /** A Japanese card by TCGdex set id and number, or null if Limitless doesn't have it either. */
    suspend fun card(setId: String, number: String): TcgCard? {
        val n = number.trimStart('0').ifEmpty { "0" }
        val key = "${setId}_$n"
        lock.withLock { if (memory.containsKey(key)) return memory[key] }
        val set = catalog.find("ja", setId) ?: return null
        val file = File(dir, "$key.json")
        val cached = withContext(Dispatchers.IO) {
            file.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < MAX_AGE }
                ?.let { f -> runCatching { f.readText() }.getOrNull() }
        }
        val card = if (cached != null) {
            if (cached.isEmpty()) null else runCatching { json.decodeFromString(TcgCard.serializer(), cached) }.getOrNull()
        } else {
            val html = try { page(set.id, n) } catch (e: IOException) { return null } // offline: try again later
            val card = html?.let(::parse)?.let { build(set, n, it) }
            withContext(Dispatchers.IO) { runCatching { file.writeText(card?.let { json.encodeToString(TcgCard.serializer(), it) } ?: "") } }
            card
        }
        lock.withLock { memory[key] = card }
        return card
    }

    private suspend fun page(setId: String, number: String): String? = withContext(Dispatchers.IO) {
        throttle.withLock {
            val wait = lastCall + 500 - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
            lastCall = SystemClock.elapsedRealtime()
        }
        val req = Request.Builder().url("$SITE/cards/jp/$setId/$number").build()
        http.newCall(req).execute().use { r ->
            when {
                r.isSuccessful -> r.body?.string()
                r.code == 404 -> null
                else -> throw IOException("Limitless error ${r.code}")
            }
        }
    }

    /**
     * The international printing in TCGdex's English data. The cheapest one Limitless lists is taken:
     * the same text is also printed as full arts and alternate arts, which cost far more.
     */
    private suspend fun english(prints: List<EnglishPrint>): Pair<TcgCard, EnglishPrint>? {
        val en = catalog.sets("en")
        for (p in prints.sortedWith(compareBy({ it.eur ?: Double.MAX_VALUE }, { it.isPromo }))) {
            val card = runCatching {
                val promo = EnglishPrint.PROMOS[p.code]
                if (promo != null) {
                    val n = p.number.trimStart('0').ifEmpty { "0" }
                    tcgdex.cardInSet(promo.first, if (promo.second.isEmpty()) n else promo.second + n.padStart(3, '0'), "en")
                } else {
                    val key = norm(p.setName)
                    en.filter { norm(it.name) == key }.asReversed().firstNotNullOfOrNull { s -> tcgdex.cardInSet(s.id, p.number, "en") }
                }
            }.getOrNull()
            if (card != null) return card to p
        }
        return null
    }

    /** The card with the international printing's Cardmarket product (found in TCGdex's English data). */
    private suspend fun build(set: TcgSetBrief, number: String, p: LimitlessPage): TcgCard {
        val english = english(p.english)
        val englishRef = english?.first?.defaultPrinting("en", false)
        val eur = english?.second?.eur ?: p.english.firstOrNull()?.eur
        val localId = if (number.all(Char::isDigit)) number.padStart(3, '0') else number
        return TcgCard(
            id = "${set.id}-$localId",
            localId = localId,
            name = p.name,
            image = p.imageBase,
            rarity = p.rarity,
            set = TcgCardSet(set.id, p.setName ?: set.name, set.cardCount),
            variantsDetailed = listOf(
                TcgVariant(
                    "normal",
                    variantId = VARIANT,
                    thirdParty = TcgThirdParty(cardmarket = englishRef?.cardmarketId),
                    pricing = TcgPricing(TcgCardmarketPricing(idProduct = englishRef?.cardmarketId, trend = englishRef?.fallbackPrice ?: eur)),
                ),
            ),
        )
    }

    companion object {
        const val SITE = "https://limitlesstcg.com"

        /** Variant id of cards made from Limitless pages: their price is the international printing's. */
        const val VARIANT = "limitless"

        private const val MAX_AGE = 30L * 24 * 3600 * 1000

        /** "Pokémon 151" and TCGdex's "151" are the same set; so are "Sun & Moon" and "Sun and Moon". */
        private fun norm(s: String) = s.lowercase().replace('é', 'e').replace("&", " and ").replace("pokemon", "")
            .filter { it.isLetterOrDigit() }

        private val nameRe = Regex("""class="card-text-name"><a[^>]*>([^<]+)</a>""")
        private val imageRe = Regex("""class="card-image">\s*<img[^>]*?src="([^"]+)"""")
        private val currentRe = Regex("""prints-current-details">\s*<span[^>]*>\s*([^<]+?)\s*</span>\s*<span>\s*#([^\s<·]+)\s*(?:·\s*([^<]+?))?\s*</span>""")
        private val englishRe = Regex("""<a\s+href="/cards/en/([^/"]+)/[^"/]+"\s*>\s*([^<]+?)\s*<span class="prints-table-card-number">#([^<]+)</span>\s*</a>\s*</td>\s*<td>.*?</td>\s*<td>\s*(?:<a[^>]*>\s*([\d.,]+)\s*€)?""", RegexOption.DOT_MATCHES_ALL)

        private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"").trim()

        /** Reads a Limitless card page; null if it isn't one. */
        fun parse(html: String): LimitlessPage? {
            val name = nameRe.find(html)?.groupValues?.get(1)?.let(::unescape)?.takeIf { it.isNotEmpty() } ?: return null
            val image = imageRe.find(html)?.groupValues?.get(1)
                ?.takeIf { it.startsWith(IMAGE_CDN) }
                ?.removeSuffix(".png")?.removeSuffix("_LG")?.removeSuffix("_SM")
            val current = currentRe.find(html)
            val setName = current?.groupValues?.get(1)?.let(::unescape)?.replace(Regex("""\s*\([^)]*\)$"""), "")
            val rarity = current?.groupValues?.get(3)?.let(::unescape)?.takeIf { it.isNotEmpty() }
            // Only the international prints table counts: it comes before the "JP. Prints" rows.
            val intl = html.substringAfter("Int. Prints", "").substringBefore("JP. Prints")
            val english = intl.split("<tr").mapNotNull { row ->
                englishRe.find(row)?.let { m ->
                    EnglishPrint(m.groupValues[1], unescape(m.groupValues[2]), m.groupValues[3].trim(), m.groupValues[4].replace(',', '.').toDoubleOrNull())
                }
            }
            return LimitlessPage(name = name, imageBase = image, setName = setName, rarity = rarity, english = english)
        }

        const val IMAGE_CDN = "https://limitlesstcg.nyc3.cdn.digitaloceanspaces.com/"
    }
}
