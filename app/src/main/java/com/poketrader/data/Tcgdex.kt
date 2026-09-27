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
) {
    /**
     * Every variant of this card as a [CardRef]: standard-size ones first (most common first),
     * then oversized (jumbo) prints, which Cardmarket sells and prices as separate products.
     */
    fun printings(dataLang: String): List<CardRef> {
        val detailed = variantsDetailed.orEmpty().sortedBy { it.isOversized }
        val variants = detailed.ifEmpty {
            val f = variants ?: TcgVariantFlags(normal = true)
            buildList {
                if (f.normal) add(TcgVariant("normal", variantId = "normal"))
                if (f.holo) add(TcgVariant("holo", variantId = "holo"))
                if (f.reverse) add(TcgVariant("reverse", variantId = "reverse"))
                if (f.firstEdition) add(TcgVariant(if (f.holo) "holo" else "normal", stamp = listOf("1st-edition"), variantId = "1st-edition"))
            }.ifEmpty { listOf(TcgVariant("normal", variantId = "normal")) }
        }
        return variants.mapIndexed { i, v ->
            val holoPrice = v.type == "reverse"
            val cm = v.pricing?.cardmarket
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
                variantId = v.variantId.ifEmpty { "v$i" },
                variantLabel = variantLabel(v),
                cardmarketId = v.thirdParty?.cardmarket ?: cm?.idProduct,
                holoPrice = holoPrice,
                fallbackPrice = (if (holoPrice) cm?.trendHolo ?: cm?.trend else cm?.trend ?: cm?.trendHolo)?.takeIf { it > 0 },
                firstEdition = "1st-edition" in v.stamp,
            )
        }.distinctBy { it.variantId }
    }

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

    suspend fun card(id: String, lang: String): TcgCard? {
        val body = call(url(lang, "cards/$id")) ?: return null
        return json.decodeFromString<TcgCard>(body)
    }

    /** A card by set and printed number; tries "25", then "025" style padding. */
    suspend fun cardInSet(setId: String, number: String, lang: String): TcgCard? {
        val stripped = number.trimStart('0').ifEmpty { "0" }
        val tries = linkedSetOf(number, stripped, stripped.padStart(3, '0'))
        for (n in tries) {
            val body = call(url(lang, "sets/$setId/$n")) ?: continue
            return json.decodeFromString<TcgCard>(body)
        }
        return null
    }

    suspend fun sets(lang: String): List<TcgSetBrief> {
        val body = call(url(lang, "sets")) ?: return emptyList()
        return json.decodeFromString<List<TcgSetBrief>>(body)
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
