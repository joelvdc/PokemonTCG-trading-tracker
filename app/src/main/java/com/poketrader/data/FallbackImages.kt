package com.poketrader.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** A set as listed by pokemontcg.io (github.com/PokemonTCG/pokemon-tcg-data). */
@Serializable
data class PtcgSet(val id: String, val name: String = "", val printedTotal: Int? = null, val total: Int? = null)

/**
 * TCGdex has no picture for some international cards (promos, special collections…). pokemontcg.io
 * often does, on its public image server, under its own set ids — so TCGdex sets are matched to
 * pokemontcg.io sets and the picture is looked up there. Japanese cards aren't on pokemontcg.io.
 */
object FallbackImages {
    const val CDN = "https://images.pokemontcg.io"

    /** TCGdex set id → pokemontcg.io set id. Compose state, so pictures appear as soon as it's loaded. */
    var setIds by mutableStateOf<Map<String, String>>(emptyMap())

    /** Image base URL for a card: TCGdex's when it has one, else pokemontcg.io's (international cards only). */
    fun base(tcgdexBase: String?, dataLang: String, setId: String, localId: String): String? =
        tcgdexBase ?: if (dataLang != "en") null else setIds[setId]?.let { "$CDN/$it/${ptcgNumber(localId)}" }

    fun thumb(base: String?): String? = base?.let {
        when {
            it.startsWith(CDN) -> "$it.png"
            it.startsWith(LimitlessCards.IMAGE_CDN) -> "${it}_SM.png"
            else -> "$it/low.webp"
        }
    }

    fun large(base: String?): String? = base?.let {
        when {
            it.startsWith(CDN) -> "${it}_hires.png"
            it.startsWith(LimitlessCards.IMAGE_CDN) -> "${it}_LG.png"
            else -> "$it/high.webp"
        }
    }

    /** pokemontcg.io writes plain numbers without leading zeros ("004" → "4"), and keeps "TG03", "SM226". */
    fun ptcgNumber(localId: String) = if (localId.all(Char::isDigit)) localId.trimStart('0').ifEmpty { "0" } else localId

    private fun norm(name: String) = name.lowercase()
        .replace('é', 'e').replace("&", " ").replace(" and ", " ")
        .filter { it.isLetterOrDigit() }

    /** "sv03.5" → "sv03.5", "sv03pt5", "sv3pt5", "sv035", "sv35"… — pokemontcg.io's spelling of the same code. */
    private fun idVariants(id: String): Set<String> {
        val base = id.lowercase()
        val v = mutableSetOf(base, base.replace(".5", "pt5"), base.replace(".", ""))
        v += v.map { it.replace(Regex("(?<=[a-z])0+(?=\\d)"), "") }
        return v
    }

    /**
     * Matches sets by name, then by set code (checked against the printed card count, unless the code
     * is identical), then by a name that ends with the other (e.g. "Unleashed" ↔ "HS—Unleashed") with
     * the same card count.
     */
    fun match(tcgdex: List<TcgSetBrief>, ptcg: List<PtcgSet>): Map<String, String> {
        val byName = ptcg.associateBy { norm(it.name) }
        val byId = ptcg.associateBy { it.id.lowercase() }
        val out = HashMap<String, String>()
        for (t in tcgdex) {
            val counts = setOf(t.cardCount.official, t.cardCount.total).filterNotNull()
            fun sameCount(p: PtcgSet) = p.printedTotal in counts || p.total in counts
            val n = norm(t.name)
            val hit = byName[n]
                ?: byId[t.id.lowercase()]
                ?: idVariants(t.id).firstNotNullOfOrNull { v -> byId[v]?.takeIf(::sameCount) }
                ?: ptcg.filter { p -> val pn = norm(p.name); (pn.endsWith(n) || n.endsWith(pn)) && sameCount(p) }.singleOrNull()
            if (hit != null) out[t.id] = hit.id
        }
        return out
    }
}

/** Loads pokemontcg.io's set list (a static file on GitHub, cached for a week) and fills [FallbackImages.setIds]. */
class FallbackImageSets(context: Context, private val http: OkHttpClient, private val catalog: SetCatalog) {
    private val file = File(context.filesDir, "ptcg_sets.json")
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val serializer = ListSerializer(PtcgSet.serializer())

    suspend fun load() {
        val cached = withContext(Dispatchers.IO) {
            if (file.exists()) runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull() else null
        }
        val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > 7L * 24 * 3600 * 1000
        val sets = (if (cached == null || stale) fetch() else null) ?: cached ?: return
        FallbackImages.setIds = FallbackImages.match(catalog.sets("en"), sets)
    }

    private suspend fun fetch(): List<PtcgSet>? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(URL).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val text = r.body?.string() ?: return@use null
                json.decodeFromString(serializer, text).also { file.writeText(text) }
            }
        }.getOrNull()
    }

    companion object {
        const val URL = "https://raw.githubusercontent.com/PokemonTCG/pokemon-tcg-data/master/sets/en.json"
    }
}
