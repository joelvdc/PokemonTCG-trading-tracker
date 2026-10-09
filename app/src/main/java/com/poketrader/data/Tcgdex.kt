package com.poketrader.data

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class TcgBrief(
    val id: String,
    val localId: String = "",
    val name: String = "",
    val image: String? = null,
) {
    /** Card ids are "<setId>-<localId>"; set ids may themselves contain dashes. */
    val setId get() = id.substringBeforeLast('-')
    fun thumbUrl(dataLang: String) = FallbackImages.thumb(FallbackImages.base(image, dataLang, setId, localId))
}

@Serializable
data class TcgCount(val official: Int? = null, val total: Int? = null)

@Serializable
data class TcgSetBrief(
    val id: String,
    val name: String = "",
    val symbol: String? = null,
    val cardCount: TcgCount = TcgCount(),
)

@Serializable
data class TcgAbbreviation(val official: String? = null)

@Serializable
data class TcgSetDetail(
    val id: String,
    val name: String = "",
    val releaseDate: String? = null,
    val abbreviation: TcgAbbreviation? = null,
)

@Serializable
data class TcgCardmarketPricing(
    val idProduct: Int? = null,
    val trend: Double? = null,
    @SerialName("trend-holo") val trendHolo: Double? = null,
)

@Serializable
data class TcgPricing(val cardmarket: TcgCardmarketPricing? = null)

@Serializable
data class TcgThirdParty(val cardmarket: Int? = null, val tcgplayer: Int? = null)

@Serializable
data class TcgVariant(
    val type: String = "normal",
    val subtype: String? = null,
    val foil: String? = null,
    val size: String? = null,
    val stamp: List<String> = emptyList(),
    val variantId: String = "",
    val thirdParty: TcgThirdParty? = null,
    val pricing: TcgPricing? = null,
) {
    /** Jumbo / oversized print (TCGdex "size" other than "standard"). */
    val isOversized get() = size != null && size != "standard"
}

@Serializable
data class TcgVariantFlags(
    val normal: Boolean = false,
    val reverse: Boolean = false,
    val holo: Boolean = false,
    val firstEdition: Boolean = false,
)

@Serializable
data class TcgCardSet(
    val id: String,
    val name: String = "",
    val cardCount: TcgCount = TcgCount(),
)

@Serializable
data class TcgAttack(val name: String = "")

/** A Cardmarket product chosen for one variant after checking TCGdex's link; see [CardmarketCatalog]. */
data class CardmarketFix(val idProduct: Int?)

@Serializable
data class TcgCard(
    val id: String,
    val localId: String = "",
    val name: String = "",
    val image: String? = null,
    val rarity: String? = null,
    val category: String? = null,
    val dexId: List<Int>? = null,
    val set: TcgCardSet,
    val variants: TcgVariantFlags? = null,
    @SerialName("variants_detailed") val variantsDetailed: List<TcgVariant>? = null,
    val attacks: List<TcgAttack>? = null,
    /** Card-level Cardmarket link; TCGdex sometimes has it here but not on the variants. */
    val thirdParty: TcgThirdParty? = null,
    val pricing: TcgPricing? = null,
    /** Corrections from [CardmarketCatalog], by variant id; not part of TCGdex's data. */
    @kotlinx.serialization.Transient val cardmarketFixes: Map<String, CardmarketFix> = emptyMap(),
) {
    /** TCGdex's variants, or ones made up from the variant flags when it has no detailed list. */
    private fun variantList(): List<TcgVariant> {
        val detailed = variantsDetailed.orEmpty().sortedBy { it.isOversized }
        return detailed.ifEmpty {
            val f = variants ?: TcgVariantFlags(normal = true)
            buildList {
                if (f.normal) add(TcgVariant("normal", variantId = "normal"))
                if (f.holo) add(TcgVariant("holo", variantId = "holo"))
                if (f.reverse) add(TcgVariant("reverse", variantId = "reverse"))
                if (f.firstEdition) add(TcgVariant(if (f.holo) "holo" else "normal", stamp = listOf("1st-edition"), variantId = "1st-edition"))
            }.ifEmpty { listOf(TcgVariant("normal", variantId = "normal")) }
        }
    }

    /** The card-level Cardmarket product, if TCGdex has one. */
    val cardLevelCardmarketId: Int? get() = thirdParty?.cardmarket ?: pricing?.cardmarket?.idProduct

    /**
     * A variant sold as the card's regular Cardmarket product: standard size, no special foil,
     * no stamp other than 1st Edition. (Stamped, patterned and jumbo prints are separate products.)
     */
    fun isPlain(v: TcgVariant) = !v.isOversized && v.foil == null && v.stamp.all { it == "1st-edition" }

    /** Each variant with the Cardmarket product TCGdex links to it, before [cardmarketFixes]. */
    fun tcgdexLinks(): List<Pair<TcgVariant, Int?>> = variantList().mapIndexed { i, v ->
        val own = v.thirdParty?.cardmarket ?: v.pricing?.cardmarket?.idProduct
        v.copy(variantId = v.variantId.ifEmpty { "v$i" }) to (own ?: if (isPlain(v)) cardLevelCardmarketId else null)
    }

    /**
     * Every variant of this card as a [CardRef]: standard-size ones first (most common first),
     * then oversized (jumbo) prints, which Cardmarket sells and prices as separate products.
     */
    fun printings(dataLang: String): List<CardRef> =
        tcgdexLinks().map { (v, linked) ->
            val holoPrice = v.type == "reverse"
            val own = v.pricing?.cardmarket
            val fix = cardmarketFixes[v.variantId]
            val cardmarketId = if (fix != null) fix.idProduct else linked
            // TCGdex's last-known price only belongs to the product it links; a corrected product has none.
            val cm = when {
                fix != null && fix.idProduct != linked -> null
                own?.idProduct != null || own?.trend != null -> own
                linked != null -> pricing?.cardmarket
                else -> null
            }
            CardRef(
                cardId = id,
                dataLang = dataLang,
                name = name,
                setId = set.id,
                setName = set.name,
                localId = localId,
                setOfficial = set.cardCount.official?.takeIf { it > 0 },
                rarity = rarity ?: "",
                imageBase = image,
                variantId = v.variantId,
                variantLabel = variantLabel(v),
                cardmarketId = cardmarketId,
                holoPrice = holoPrice,
                fallbackPrice = (if (holoPrice) cm?.trendHolo ?: cm?.trend else cm?.trend ?: cm?.trendHolo)?.takeIf { it > 0 },
                firstEdition = "1st-edition" in v.stamp,
            )
        }.distinctBy { it.variantId }

    /** The variant to use when none was chosen: plain first, or the first reverse holo if [preferHolo]. */
    fun defaultPrinting(dataLang: String, preferHolo: Boolean): CardRef {
        val all = printings(dataLang)
        // Never guess a 1st Edition or an oversized card: those must be picked on purpose.
        val plain = all.filter { !it.firstEdition && !it.oversized }
        return if (preferHolo) {
            plain.firstOrNull { it.holoPrice } ?: plain.firstOrNull { it.variantLabel.startsWith("Holo") } ?: all.first()
        } else {
            plain.firstOrNull { !it.holoPrice } ?: all.first()
        }
    }
}

private fun titleCase(s: String) = s.split('-', '_', ' ').filter { it.isNotEmpty() }
    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

/**
 * Human-readable name for a variant, e.g. "Reverse Holo · Master Ball", "Holo · Shadowless · 1st Edition"
 * or "Jumbo". Oversized labels always start with [CardRef.OVERSIZED_LABELS].
 */
fun variantLabel(v: TcgVariant): String {
    val parts = mutableListOf<String>()
    if (v.isOversized) parts += if (v.size == "jumbo") "Jumbo" else "Oversized (${titleCase(v.size ?: "")})"
    val finish = when (v.type) {
        "reverse" -> "Reverse Holo"
        "holo" -> "Holo"
        "normal" -> "Normal"
        else -> titleCase(v.type)
    }
    // "Jumbo" alone reads better than "Jumbo · Normal".
    if (!(v.isOversized && v.type == "normal")) parts += finish
    v.foil?.let {
        parts += when (it) {
            "pokeball" -> "Poké Ball"
            "masterball" -> "Master Ball"
            else -> titleCase(it)
        }
    }
    v.subtype?.takeIf { it != "unlimited" }?.let { parts += if (it == "1999-2000-copyright") "1999-2000 ©" else titleCase(it) }
    v.stamp.forEach { parts += if (it == "1st-edition") "1st Edition" else "${titleCase(it)} stamp" }
    return parts.joinToString(" · ")
}

@Serializable
private data class TcgSerie(val id: String, val sets: List<TcgSetBrief> = emptyList())

/** One TCGdex series ("Scarlet & Violet"…), as listed by /series. Since 1.13. */
@Serializable
data class TcgSerieBrief(val id: String, val name: String = "")

@Serializable
private data class TcgSetCards(val id: String = "", val cards: List<TcgBrief> = emptyList())

/**
 * Minimal TCGdex REST client (api.tcgdex.net). [lang] is TCGdex's language code:
 * "en", "fr", "de", "it", "es", "pt" or "ja".
 */
class TcgdexApi(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private val throttle = Mutex()
    private var lastCall = 0L
    private val base = "https://api.tcgdex.net/v2/".toHttpUrl()

    private suspend fun waitTurn() = throttle.withLock {
        val wait = lastCall + 60 - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        lastCall = SystemClock.elapsedRealtime()
    }

    /** Returns the body, or null on 404. Throws [IOException] on other failures. */
    private suspend fun call(url: HttpUrl): String? = withContext(Dispatchers.IO) {
        waitTurn()
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            when {
                r.isSuccessful -> r.body?.string()
                r.code == 404 -> null
                else -> throw IOException("TCGdex error ${r.code}")
            }
        }
    }

    private fun url(lang: String, path: String, vararg query: Pair<String, String>): HttpUrl {
        val b = base.newBuilder().addPathSegment(lang)
        path.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private suspend fun briefs(url: HttpUrl): List<TcgBrief> {
        val body = call(url) ?: return emptyList()
        return json.decodeFromString<List<TcgBrief>>(body)
    }

    /** Cards whose name contains [query] (case-insensitive). */
    suspend fun searchByName(query: String, lang: String = "en"): List<TcgBrief> = briefs(url(lang, "cards", "name" to query))

    suspend fun cardsNamed(name: String, lang: String = "en"): List<TcgBrief> = briefs(url(lang, "cards", "name" to "eq:$name"))

    /** All cards of one Pokémon by National Pokédex number — works across languages (e.g. to find Japanese prints). */
    suspend fun cardsByDex(dexId: Int, lang: String): List<TcgBrief> = briefs(url(lang, "cards", "dexId" to "eq:$dexId"))

    /**
     * Checks and corrects a card's Cardmarket links (see [CardmarketCatalog]); set once at start-up.
     * Cards come back unchanged until it's set, or if it fails.
     */
    @Volatile
    var repair: (suspend (TcgCard, String) -> TcgCard)? = null

    /**
     * Japanese cards TCGdex is missing, from another source ([LimitlessCards]); set once at start-up.
     * Takes the set id and the printed number. Since 1.12.
     */
    @Volatile
    var missingJapanese: (suspend (String, String) -> TcgCard?)? = null

    private suspend fun missing(setId: String, number: String, lang: String): TcgCard? =
        if (lang != "ja") null else missingJapanese?.let { f -> runCatching { f(setId, number) }.getOrNull() }

    private suspend fun repaired(card: TcgCard, lang: String): TcgCard =
        repair?.let { fix -> runCatching { fix(card, lang) }.getOrNull() } ?: card

    suspend fun card(id: String, lang: String): TcgCard? =
        cardAsIs(id, lang)?.let { repaired(it, lang) } ?: missing(id.substringBeforeLast('-'), id.substringAfterLast('-'), lang)

    /** A card exactly as TCGdex has it, without Cardmarket corrections. */
    suspend fun cardAsIs(id: String, lang: String): TcgCard? {
        val body = call(url(lang, "cards/$id")) ?: return null
        return json.decodeFromString<TcgCard>(body)
    }

    /** A card by set and printed number; tries "25", then "025" style padding. */
    suspend fun cardInSet(setId: String, number: String, lang: String): TcgCard? {
        val stripped = number.trimStart('0').ifEmpty { "0" }
        val tries = linkedSetOf(number, stripped, stripped.padStart(3, '0'))
        for (n in tries) {
            val body = call(url(lang, "sets/$setId/$n")) ?: continue
            return repaired(json.decodeFromString<TcgCard>(body), lang)
        }
        return missing(setId, number, lang)
    }

    /** The cards of one set (brief form). */
    suspend fun setCards(setId: String, lang: String): List<TcgBrief> {
        val body = call(url(lang, "sets/$setId")) ?: return emptyList()
        return json.decodeFromString<TcgSetCards>(body).cards
    }

    suspend fun sets(lang: String): List<TcgSetBrief> {
        val body = call(url(lang, "sets")) ?: return emptyList()
        return json.decodeFromString<List<TcgSetBrief>>(body)
    }

    /** Every series, oldest first. Since 1.13. */
    suspend fun series(lang: String): List<TcgSerieBrief> {
        val body = call(url(lang, "series")) ?: return emptyList()
        return json.decodeFromString<List<TcgSerieBrief>>(body)
    }

    /** Set ids of one series, e.g. "tcgp" (Pokémon TCG Pocket, a digital-only game). */
    suspend fun serieSetIds(serieId: String, lang: String): List<String> {
        val body = call(url(lang, "series/$serieId")) ?: return emptyList()
        return json.decodeFromString<TcgSerie>(body).sets.map { it.id }
    }

    suspend fun setDetail(setId: String, lang: String): TcgSetDetail? {
        val body = call(url(lang, "sets/$setId")) ?: return null
        return json.decodeFromString<TcgSetDetail>(body)
    }
}
