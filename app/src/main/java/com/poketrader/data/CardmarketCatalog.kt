package com.poketrader.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

/** One Pokémon single from Cardmarket's public product list. Since version 1.6. */
@Entity(tableName = "cm_products", indices = [Index("idExpansion")])
data class CmProduct(
    @PrimaryKey val idProduct: Int,
    /** E.g. "Nidoran [M] [Horn Hazard]": the card name, then its attacks in brackets. */
    val name: String,
    val idExpansion: Int,
)

/** Which Cardmarket expansion a TCGdex set is, once worked out ("en/lc" → 1535). */
@Entity(tableName = "cm_set_expansions")
data class CmSetExpansion(@PrimaryKey val key: String, val idExpansion: Int)

@Dao
interface CatalogDao {
    @Query("DELETE FROM cm_products")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<CmProduct>)

    @Query("SELECT * FROM cm_products WHERE idProduct = :id")
    suspend fun get(id: Int): CmProduct?

    @Query("SELECT * FROM cm_products WHERE idProduct IN (:ids)")
    suspend fun getMany(ids: List<Int>): List<CmProduct>

    @Query("SELECT * FROM cm_products WHERE idExpansion = :idExpansion")
    suspend fun inExpansion(idExpansion: Int): List<CmProduct>

    @Query("SELECT COUNT(*) FROM cm_products")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM cm_products")
    fun observeCount(): Flow<Int>

    @Query("SELECT idExpansion FROM cm_set_expansions WHERE `key` = :key")
    suspend fun setExpansion(key: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSetExpansion(row: CmSetExpansion)

    @Query("DELETE FROM cm_set_expansions")
    suspend fun clearSetExpansions()
}

/**
 * Compares TCGdex cards with Cardmarket product names. Cardmarket names look like
 * "Erika's Bellsprout [Careless Tackle]", "Nidoran [M] [Horn Hazard]", "Dialga Lv.68 [Time Bellow | Flash Cannon]"
 * or "Charmander δ Delta Species [Scratch | Bite]".
 */
object CatalogMatch {
    private val brackets = Regex("""\s*\[[^\]]*]""")
    private val level = Regex("""\bLv\.\s*\d+""", RegexOption.IGNORE_CASE)

    /** The card name part of a product name: no attacks, level or "Delta Species"; ♂/♀ as on the card. */
    fun baseName(productName: String): String =
        productName.replace("[M]", "♂").replace("[F]", "♀")
            .replace(brackets, "")
            .replace(level, "")
            .replace("Delta Species", "", ignoreCase = true)
            .trim()

    /** The attacks listed in a product name's last bracket, e.g. ["Poison Vine", "Vine Whip"]. */
    fun attacks(productName: String): List<String> {
        val groups = Regex("""\[([^\]]*)]""").findAll(productName).map { it.groupValues[1] }
            .filter { it != "M" && it != "F" }.toList()
        return groups.lastOrNull()?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }

    fun normalize(s: String): String =
        s.lowercase().replace("♂", "m").replace("♀", "f").replace('é', 'e').filter { it.isLetterOrDigit() || it == 'δ' }

    /** True when the product is this card by name (attacks aside). */
    fun sameCard(cardName: String, productName: String) = normalize(cardName) == normalize(baseName(productName))

    /**
     * True when the product is this card: same name and, when both list attacks, at least one in
     * common. (Two cards can share a name, e.g. Hidden Fates' Charizard GX 9/68 [Flamethrower |
     * Flare Blitz GX] and the shiny one [Wing Attack | Crimson Storm | Raging Out GX].)
     */
    fun isThisCard(cardName: String, cardAttacks: List<String>, productName: String): Boolean {
        if (!sameCard(cardName, productName)) return false
        val theirs = attacks(productName)
        if (cardAttacks.isEmpty() || theirs.isEmpty()) return true
        val ours = cardAttacks.map(::normalize).toSet()
        return theirs.any { normalize(it) in ours }
    }

    /**
     * The product in [candidates] that is this card: same name and, when both list attacks, at
     * least one attack in common (the most in common wins). Identical duplicates (e.g. a set's
     * reprint products) resolve to the oldest; anything still ambiguous gives null.
     */
    fun pick(candidates: List<CmProduct>, cardName: String, cardAttacks: List<String>): Int? {
        val same = candidates.filter { sameCard(cardName, it.name) }
        if (same.isEmpty()) return null
        val wanted = cardAttacks.map(::normalize).toSet()
        val scored = same.map { p -> p to attacks(p.name).count { normalize(it) in wanted } }
        val best = scored.maxOf { it.second }
        if (wanted.isNotEmpty() && best == 0 && same.any { attacks(it.name).isNotEmpty() }) return null
        val top = scored.filter { it.second == best }.map { it.first }
        return if (top.map { it.name }.distinct().size == 1) top.minOf { it.idProduct } else null
    }
}

/**
 * Cardmarket's public list of Pokémon singles, downloaded about once a week. TCGdex links each
 * card to a Cardmarket product, but some links are missing and a few point to the wrong card
 * (e.g. Gym Heroes' "Erika's Bellsprout" → "Erika"); this list lets the app find the right one.
 */
class CardmarketCatalog(
    private val context: Context,
    private val http: OkHttpClient,
    private val db: AppDatabase,
    private val settings: Settings,
    private val tcgdex: TcgdexApi,
) {
    companion object {
        const val URL = "https://downloads.s3.cardmarket.com/productCatalog/productList/products_singles_6.json"
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val SIBLINGS_TO_TRY = 8
    }

    private val dao = db.catalogDao()
    private val lock = Mutex()
    private val _state = MutableStateFlow<PriceUpdateState>(PriceUpdateState.Idle)
    val state: StateFlow<PriceUpdateState> = _state
    val count = dao.observeCount()

    @Volatile
    private var loaded: Boolean? = null

    val isStale get() = System.currentTimeMillis() - settings.catalogFetchedAt.value > MAX_AGE_MS

    /** Downloads and stores the product list. */
    suspend fun refresh(): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val tmp = File(context.cacheDir, "cm_products.json")
            try {
                var attempt = 1
                while (true) {
                    try {
                        download(tmp)
                        break
                    } catch (e: IOException) {
                        if (attempt >= 3) throw e
                        _state.value = PriceUpdateState.Running("Connection lost, retrying…")
                        kotlinx.coroutines.delay(2000L * attempt)
                        attempt++
                    }
                }
                _state.value = PriceUpdateState.Running("Saving the Cardmarket card list…")
                val createdAt = import(tmp)
                settings.setCatalogFetched(createdAt, System.currentTimeMillis())
                loaded = true
                _state.value = PriceUpdateState.Idle
                true
            } catch (e: Exception) {
                _state.value = PriceUpdateState.Failed(e.message ?: e.javaClass.simpleName)
                false
            } finally {
                tmp.delete()
            }
        }
    }

    private fun download(target: File) {
        _state.value = PriceUpdateState.Running("Downloading the Cardmarket card list…")
        val req = Request.Builder().url(URL).header("User-Agent", "PokeTraderAndroid/1.0").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("Empty response")
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastMb = -1L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val mb = done / (1024 * 1024)
                        if (mb != lastMb) {
                            lastMb = mb
                            val of = if (total > 0) " of ${total / (1024 * 1024)} MB" else " MB"
                            _state.value = PriceUpdateState.Running("Downloading the Cardmarket card list… $mb$of")
                        }
                    }
                }
            }
        }
    }

    private suspend fun import(file: File): String? {
        var createdAt: String? = null
        db.withTransaction {
            dao.clear()
            // Product ids can move between expansions; work set → expansion out again.
            dao.clearSetExpansions()
            JsonReader(InputStreamReader(file.inputStream().buffered(), Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "createdAt" -> createdAt = reader.nextString()
                        "products" -> {
                            val batch = ArrayList<CmProduct>(2000)
                            reader.beginArray()
                            while (reader.hasNext()) {
                                readProduct(reader)?.let { batch += it }
                                if (batch.size >= 2000) {
                                    dao.insertAll(batch)
                                    batch.clear()
                                }
                            }
                            reader.endArray()
                            if (batch.isNotEmpty()) dao.insertAll(batch)
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }
        return createdAt
    }

    private fun readProduct(r: JsonReader): CmProduct? {
        var id: Int? = null
        var name: String? = null
        var expansion: Int? = null
        r.beginObject()
        while (r.hasNext()) {
            val key = r.nextName()
            if (r.peek() == JsonToken.NULL) {
                r.nextNull()
                continue
            }
            when (key) {
                "idProduct" -> id = r.nextInt()
                "name" -> name = r.nextString()
                "idExpansion" -> expansion = r.nextInt()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return if (id != null && name != null && expansion != null) CmProduct(id, name, expansion) else null
    }

    suspend fun hasList(): Boolean = loaded ?: (dao.count() > 0).also { loaded = it }

    /** True if [cardmarketId] is known to be a different card than [cardName]. */
    suspend fun looksWrong(cardName: String, cardmarketId: Int): Boolean {
        val p = dao.get(cardmarketId) ?: return false
        return !CatalogMatch.sameCard(cardName, p.name)
    }

    /**
     * Checks each variant's Cardmarket link: a missing link on a plain variant, or a link to a
     * product with another name, is looked up in the card's expansion. A link is only replaced
     * when the right product is found with confidence. Japanese cards (Japanese names) are left as they are.
     */
    suspend fun repair(card: TcgCard, lang: String): TcgCard {
        if (lang == "ja" || !hasList()) return card
        val links = card.tcgdexLinks()
        val products = dao.getMany(links.mapNotNull { it.second }.distinct()).associateBy { it.idProduct }
        val attacks = card.attacks.orEmpty().map { it.name }
        val fixes = HashMap<String, CardmarketFix>()
        var lookedUp = false
        var found: Int? = null
        for ((variant, linked) in links) {
            val product = linked?.let { products[it] }
            val wrong = product != null && !CatalogMatch.isThisCard(card.name, attacks, product.name)
            if (!(linked == null || wrong) || !card.isPlain(variant)) continue
            if (!lookedUp) {
                lookedUp = true
                val expansion = expansionFor(card, lang, products.values)
                found = expansion?.let { CatalogMatch.pick(dao.inExpansion(it), card.name, attacks) }
            }
            if (found != null && found != linked) fixes[variant.variantId] = CardmarketFix(found)
        }
        return if (fixes.isEmpty()) card else card.copy(cardmarketFixes = fixes)
    }

    /**
     * The Cardmarket expansion of the card's set: from the card's own correctly named links, else
     * remembered from before, else worked out from a few other cards of the set.
     */
    private suspend fun expansionFor(card: TcgCard, lang: String, linked: Collection<CmProduct>): Int? {
        val key = "$lang/${card.set.id}"
        linked.firstOrNull { CatalogMatch.isThisCard(card.name, card.attacks.orEmpty().map { a -> a.name }, it.name) }?.let { p ->
            dao.putSetExpansion(CmSetExpansion(key, p.idExpansion))
            return p.idExpansion
        }
        dao.setExpansion(key)?.let { return it }
        val siblings = runCatching { tcgdex.setCards(card.set.id, lang) }.getOrDefault(emptyList()).filter { it.id != card.id }
        if (siblings.isEmpty()) return null
        val step = maxOf(1, siblings.size / SIBLINGS_TO_TRY)
        val votes = HashMap<Int, Int>()
        for (b in siblings.filterIndexed { i, _ -> i % step == 0 }.take(SIBLINGS_TO_TRY)) {
            val sibling = runCatching { tcgdex.cardAsIs(b.id, lang) }.getOrNull() ?: continue
            val ids = sibling.tcgdexLinks().mapNotNull { it.second }.distinct()
            for (p in dao.getMany(ids)) {
                if (!CatalogMatch.sameCard(sibling.name, p.name)) continue
                val n = (votes[p.idExpansion] ?: 0) + 1
                votes[p.idExpansion] = n
                if (n >= 2) {
                    dao.putSetExpansion(CmSetExpansion(key, p.idExpansion))
                    return p.idExpansion
                }
            }
        }
        return votes.maxByOrNull { it.value }?.key?.also { dao.putSetExpansion(CmSetExpansion(key, it)) }
    }
}
