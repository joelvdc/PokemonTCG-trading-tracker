package com.poketrader.scan

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.poketrader.data.SetCatalog
import com.poketrader.data.TcgBrief
import com.poketrader.data.TcgCard
import com.poketrader.data.TcgdexApi
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

data class Candidate(val brief: TcgBrief, val setName: String)

sealed interface ScanResult {
    /** [language] is the card's print language ("EN", "FR", "JA"…) when it could be told. */
    data class Found(val card: TcgCard, val dataLang: String, val language: String?, val exact: Boolean) : ScanResult

    /**
     * The name was read but several printings fit: let the user pick by picture. [missing] is the
     * printing that was read but isn't in the card database ("Pokémon GO (S10b) #043"). Since 1.12.
     */
    data class Choose(
        val name: String,
        val candidates: List<Candidate>,
        val dataLang: String,
        val language: String?,
        val missing: String? = null,
    ) : ScanResult
}

/** Turns OCR clues into a TCGdex card. Lookups (including misses) are cached per clue. */
class CardRecognizer(private val api: TcgdexApi, private val catalog: SetCatalog) {
    private val cardCache = HashMap<String, TcgCard?>()
    private val listCache = HashMap<String, List<TcgBrief>>()

    private suspend fun cachedCard(key: String, fetch: suspend () -> TcgCard?): TcgCard? {
        if (cardCache.containsKey(key)) return cardCache[key]
        val v = try { fetch() } catch (e: IOException) { return null }
        cardCache[key] = v
        return v
    }

    private suspend fun cachedList(key: String, fetch: suspend () -> List<TcgBrief>): List<TcgBrief>? {
        listCache[key]?.let { return it }
        val v = try { fetch() } catch (e: IOException) { return null }
        listCache[key] = v
        return v
    }

    /** Set and number that were read but have no card in TCGdex (its Japanese data has gaps), for the user. */
    var lastMissing: String? = null
        private set

    suspend fun identify(c: ScanClues): ScanResult? {
        val dataLang = c.dataLang
        lastMissing = null
        if (c.number != null) byNumber(c, dataLang)?.let { return it }
        if (c.name != null) byName(c, dataLang)?.let { r ->
            return if (r is ScanResult.Choose) r.copy(missing = lastMissing) else r
        }
        return null
    }

    private suspend fun byNumber(c: ScanClues, dataLang: String): ScanResult? {
        val number = c.number ?: return null
        var sets = c.total?.let { catalog.withCount(dataLang, it) }.orEmpty()
        val code = c.setCode
        var codeMatched = false
        if (code != null) {
            if (dataLang == "ja") {
                val direct = catalog.find("ja", code)
                    // A misread code: the set with this card count whose id is closest to what was read.
                    ?: sets.map { it to CardTextParser.codeSimilarity(code, it.id) }.filter { it.second >= 0.5 }.maxByOrNull { it.second }?.first
                if (direct != null) {
                    sets = listOf(direct) + sets.filter { it.id != direct.id }
                    codeMatched = true
                }
            } else {
                val matching = sets.take(8).filter { catalog.abbreviation("en", it.id).equals(code, ignoreCase = true) }
                if (matching.isNotEmpty()) {
                    sets = matching
                    codeMatched = true
                } else if (sets.size <= 8) {
                    // A printed set code no set with this card count has (e.g. CLV, Trading Card Game Classic).
                    lastMissing = "$code $number"
                }
            }
        }
        if (codeMatched) {
            val set = sets.first()
            val card = cachedCard("$dataLang/${set.id}/$number") { api.cardInSet(set.id, number, dataLang) }
            if (card == null) {
                // The set is known but TCGdex has no such card in it (many Japanese sets are incomplete):
                // don't guess from other sets, let the name search offer same-name cards instead.
                lastMissing = "${set.id} #$number"
                return null
            }
            // No name to check (a Korean card): the printed set code is what makes the number trustworthy.
            if (c.name == null) return ScanResult.Found(card, dataLang, c.language ?: if (dataLang == "ja") "JA" else null, exact = true)
        }
        val found = mutableListOf<Triple<TcgCard, Double, String?>>()
        for (s in sets.take(6)) {
            val card = cachedCard("$dataLang/${s.id}/$number") { api.cardInSet(s.id, number, dataLang) } ?: continue
            val (score, lang) = nameScore(card, c, dataLang)
            found += Triple(card, score, lang)
            if (score >= 0.85) break
        }
        val best = found.maxByOrNull { it.second } ?: return null
        val language = c.language ?: best.third
        return when {
            c.name != null && best.second >= 0.6 -> ScanResult.Found(best.first, dataLang, language, exact = true)
            // Without a name, only the printed set code makes the number trustworthy; otherwise show the picture first.
            c.name == null && found.size == 1 && codeMatched -> ScanResult.Found(best.first, dataLang, language, exact = true)
            c.name == null -> ScanResult.Choose(
                "#$number",
                found.map { Candidate(TcgBrief(it.first.id, it.first.localId, it.first.name, it.first.image), it.first.set.name) },
                dataLang, language, lastMissing,
            )
            else -> null // the name doesn't fit this number: the number was probably misread
        }
    }

    /**
     * How well the OCR'd name fits [card] (0..1), and in which language it matched. International
     * prints share numbers across languages, so a French name is checked against the French card data.
     */
    private suspend fun nameScore(card: TcgCard, c: ScanClues, dataLang: String): Pair<Double, String?> {
        val name = c.name ?: return 0.0 to null
        val direct = CardTextParser.similarity(name, card.name)
        if (dataLang == "ja" || direct >= 0.6) return direct to (if (dataLang == "ja") "JA" else c.language ?: "EN")
        val langs = c.language?.let { listOf(it.lowercase()) }?.filter { it != "en" } ?: listOf("fr", "de", "it", "es", "pt")
        var best = direct to (c.language ?: "EN")
        for (l in langs) {
            val local = cachedCard("$l/${card.id}") { api.card(card.id, l) } ?: continue
            val s = CardTextParser.similarity(name, local.name)
            if (s > best.first) best = s to l.uppercase()
            if (s >= 0.85) break
        }
        return best
    }

    private suspend fun byName(c: ScanClues, dataLang: String): ScanResult? {
        val name = c.name ?: return null
        val searchLang = when {
            dataLang == "ja" -> "ja"
            c.language != null && c.language != "EN" -> c.language.lowercase()
            else -> "en"
        }
        catalog.sets(dataLang) // makes sure the digital-set list is loaded
        val raw = (cachedList("$searchLang/$name") { api.searchByName(name, searchLang) } ?: return null)
            .filter { !catalog.isDigital(dataLang, it.setId) }
        val scored = raw.map { it to CardTextParser.similarity(name, it.name) }
        val top = scored.maxOfOrNull { it.second } ?: return null
        if (top < 0.75) return null
        // Keep only the best-matching name ("Pikachu", not also "Pikachu ex").
        var matches = scored.filter { it.second >= top - 0.001 }.map { it.first }
        c.number?.let { n ->
            val stripped = n.trimStart('0')
            val byNum = matches.filter { it.localId.trimStart('0').equals(stripped, ignoreCase = true) }
            if (byNum.isNotEmpty()) matches = byNum
        }
        if (matches.size == 1 && lastMissing == null) {
            val b = matches.first()
            val card = cachedCard("$dataLang/${b.id}") { api.card(b.id, dataLang) } ?: return null
            return ScanResult.Found(card, dataLang, c.language ?: if (dataLang == "ja") "JA" else searchLang.uppercase(), exact = true)
        }
        val order = matches.associateWith { catalog.order(dataLang, it.setId).let { o -> if (o == Int.MAX_VALUE) -1 else o } }
        val ordered = matches.sortedByDescending { order[it] }
        val candidates = ordered.take(60).map { b -> Candidate(b, catalog.find(dataLang, b.setId)?.name ?: b.setId) }
        return ScanResult.Choose(matches.first().name, candidates, dataLang, c.language ?: if (dataLang == "ja") "JA" else null)
    }
}

/**
 * CameraX analyzer running ML Kit on-device text recognition (the Japanese model, which also
 * reads Latin script); reports parsed clues on the main thread.
 */
class CardTextAnalyzer(private val onClues: (ScanClues) -> Unit) : ImageAnalysis.Analyzer {
    private val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    val paused = AtomicBoolean(false)

    /** The last few frames' foil readings (see [FoilMeter]), for the card in front of the camera. */
    private val shine = ArrayDeque<ShineSample>()

    @Synchronized
    fun shineSamples(): List<ShineSample> = shine.toList()

    @Synchronized
    fun resetShine() = shine.clear()

    @Synchronized
    private fun addShine(s: ShineSample) {
        shine.addLast(s)
        while (shine.size > 10) shine.removeFirst()
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || paused.get()) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val w = if (rotation % 180 == 0) proxy.width else proxy.height
        val h = if (rotation % 180 == 0) proxy.height else proxy.width
        FoilMeter.sample(media, rotation, ScanGuide.boxFor(w.toFloat(), h.toFloat()), w, h)?.let(::addShine)
        recognizer.process(InputImage.fromMediaImage(media, rotation))
            .addOnSuccessListener { text ->
                val lines = text.textBlocks.flatMap { b ->
                    b.lines.mapNotNull { l -> l.boundingBox?.let { r -> OcrLine(l.text, r.left, r.top, r.right, r.bottom) } }
                }
                onClues(CardTextParser.parse(lines, ScanGuide.boxFor(w.toFloat(), h.toFloat())))
            }
            .addOnCompleteListener { proxy.close() }
    }

    fun close() = recognizer.close()
}
