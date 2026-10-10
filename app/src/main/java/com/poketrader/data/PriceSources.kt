package com.poketrader.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Where a price comes from. Cardmarket's are in euros; TCGplayer's in US dollars. Since 1.16. */
enum class PriceSource(val key: String, val label: String) {
    CARDMARKET("cardmarket", "Cardmarket"),
    TCGPLAYER("tcgplayer", "TCGplayer");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: CARDMARKET
    }
}

/** One card version's TCGplayer market price in US dollars, kept on the phone. [cardKey] is [Pricing.key]. Since 1.16. */
@Entity(tableName = "source_prices", primaryKeys = ["cardKey", "source"])
data class SourcePrice(
    val cardKey: String,
    val source: String,
    val price: Double?,
    val url: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface SourcePriceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(rows: List<SourcePrice>)

    @Query("SELECT * FROM source_prices WHERE cardKey IN (:keys)")
    suspend fun forKeys(keys: List<String>): List<SourcePrice>
}

/**
 * The price of one copy, from the source chosen in Settings, in euros. TCGplayer has one market
 * price per card version (not per condition), for English prints; a card it has no price for
 * falls back to Cardmarket's ([isApprox]). Compose state, so screens redraw when the source or the
 * prices change. Since 1.16.
 */
object Pricing {
    var source by mutableStateOf(PriceSource.CARDMARKET)

    /** How many US dollars one euro buys; null until the exchange rates are known. */
    var usdPerEuro by mutableStateOf<Double?>(null)

    /** [key] → TCGplayer price (US dollars) and link, for the cards loaded so far. */
    var tcgplayer by mutableStateOf<Map<String, SourcePrice>>(emptyMap())

    fun key(card: CardRef) = "${card.cardId}|${card.dataLang}|${card.variantId}"

    /** A copy's price at [source], in euros; null when it has none. [cardmarket] is Cardmarket's price for it. */
    fun at(source: PriceSource, card: CardRef, cardmarket: Double?): Double? = when (source) {
        PriceSource.CARDMARKET -> cardmarket
        PriceSource.TCGPLAYER -> {
            val rate = usdPerEuro
            if (rate == null) null else tcgplayer[key(card)]?.price?.takeIf { it > 0 }?.div(rate)
        }
    }

    /** The price of one copy from the chosen source, or Cardmarket's when that source has none. */
    fun unit(card: CardRef, cardmarket: Double?): Double? = at(source, card, cardmarket) ?: cardmarket

    /** True when [unit] fell back to Cardmarket's price (shown with "≈"). */
    fun isApprox(card: CardRef): Boolean = source != PriceSource.CARDMARKET && at(source, card, null) == null

    fun url(card: CardRef): String? = tcgplayer[key(card)]?.url
}

/**
 * TCGplayer's market price of each of [card]'s versions, by variant id. Only plain versions match a
 * TCGplayer product for sure (normal, holo, reverse holo, 1st edition); stamped, special-pattern,
 * other-print and jumbo versions get none, so they keep Cardmarket's price rather than a wrong one.
 */
fun tcgplayerPrices(card: TcgCard): Map<String, SourcePrice> {
    val prices = card.pricing?.tcgplayer ?: return emptyMap()
    val now = System.currentTimeMillis()
    return card.tcgdexLinks().mapNotNull { (v, _) ->
        val keys = tcgplayerKeys(v) ?: return@mapNotNull null
        val entry = keys.firstNotNullOfOrNull { k -> (prices[k] as? JsonObject) } ?: return@mapNotNull null
        val usd = entry["marketPrice"]?.jsonPrimitive?.doubleOrNull ?: entry["midPrice"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val product = entry["productId"]?.jsonPrimitive?.intOrNull ?: v.thirdParty?.tcgplayer
        v.variantId to SourcePrice("", PriceSource.TCGPLAYER.key, usd.takeIf { it > 0 }, product?.let { "https://www.tcgplayer.com/product/$it" }, now)
    }.toMap()
}

/** TCGplayer's price keys for a TCGdex variant, most likely first; null when it has no certain match. */
fun tcgplayerKeys(v: TcgVariant): List<String>? {
    if (v.isOversized || v.foil != null) return null
    if (v.stamp.any { it != "1st-edition" }) return null
    if (v.subtype != null && v.subtype != "unlimited") return null
    val first = "1st-edition" in v.stamp
    return when (v.type) {
        "reverse" -> listOf("reverse-holofoil")
        "holo" -> if (first) listOf("1stEditionHolofoil") else listOf("holofoil", "unlimitedHolofoil")
        "normal" -> if (first) listOf("1stEditionNormal") else listOf("normal", "unlimited")
        else -> null
    }
}

/**
 * Downloads TCGplayer's prices (they come with TCGdex's card data, one card at a time) for the
 * cards you have, and keeps the ones of every card TCGdex sends anyway. Since 1.16.
 */
class PriceSourceStore(context: Context, private val db: AppDatabase, private val tcgdex: TcgdexApi) {
    private val dao = db.sourcePriceDao()
    private val prefs = context.getSharedPreferences("price_sources", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val _status = MutableStateFlow(Status(prefs.getLong(K_AT, 0), error = prefs.getString(K_ERR, null)))
    val status: StateFlow<Status> = _status

    data class Status(val tcgplayerAt: Long = 0, val running: Boolean = false, val done: Int = 0, val total: Int = 0, val error: String? = null)

    /** Once a day; after a failed try, not again for [RETRY_MS] (since 1.17). */
    val isStale get() = isDue(prefs.getLong(K_AT, 0), prefs.getLong(K_FAIL, 0), System.currentTimeMillis())

    private suspend fun ownedCards(): List<CardRef> {
        val d = db
        return (d.collectionDao().all().map { it.card } + d.wishlistDao().all().map { it.card } +
            d.tradeDao().all().flatMap { t -> t.items.map { it.card } } + d.scanDao().all().map { it.card }).distinctBy { Pricing.key(it) }
    }

    /** Loads the saved prices of the cards you have into [Pricing]. */
    suspend fun loadOwned() = load(ownedCards().map(Pricing::key))

    suspend fun load(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val rows = keys.distinct().chunked(900).flatMap { dao.forKeys(it) }.filter { it.source == PriceSource.TCGPLAYER.key }
        Pricing.tcgplayer = Pricing.tcgplayer + rows.associateBy { it.cardKey }
    }

    /** Saves the TCGplayer prices of a card TCGdex sent (any language: Japanese prints simply have none). */
    suspend fun fromTcgdex(card: TcgCard, lang: String) {
        val rows = tcgplayerPrices(card).map { (variant, p) -> p.copy(cardKey = "${card.id}|$lang|$variant") }
        if (rows.isEmpty()) return
        dao.putAll(rows)
        Pricing.tcgplayer = Pricing.tcgplayer + rows.associateBy { it.cardKey }
    }

    suspend fun refreshIfStale() {
        if (isStale) refresh()
    }

    /** Fetches every card you have (English prints) again from TCGdex, for its TCGplayer prices. */
    suspend fun refresh(): Boolean = lock.withLock {
        val ids = ownedCards().filter { !it.isJapanese }.map { it.cardId }.distinct()
        _status.value = _status.value.copy(running = true, done = 0, total = ids.size, error = null)
        val start = System.currentTimeMillis()
        var failed = 0
        for ((i, id) in ids.withIndex()) {
            // cardAsIs passes the card to fromTcgdex (see AppContainer).
            if (runCatching { tcgdex.cardAsIs(id, "en") }.isFailure) failed++
            if (i % 5 == 0) _status.value = _status.value.copy(done = i + 1)
        }
        val error = if (failed > 0) "$failed of ${ids.size} cards couldn't be read" else null
        prefs.edit().apply {
            if (failed < ids.size || ids.isEmpty()) putLong(K_AT, start).remove(K_FAIL) else putLong(K_FAIL, start)
            if (error != null) putString(K_ERR, error) else remove(K_ERR)
        }.apply()
        loadOwned()
        _status.value = Status(prefs.getLong(K_AT, 0), error = error)
        failed == 0
    }

    companion object {
        private const val K_AT = "tcgplayerAt"
        private const val K_FAIL = "tcgplayerFailedAt"
        private const val K_ERR = "tcgplayerError"
        private const val MAX_AGE_MS = 20L * 60 * 60 * 1000
        /** After a failed automatic download, wait this long before trying again. */
        const val RETRY_MS = 6L * 60 * 60 * 1000

        /** Due when the last download is over [MAX_AGE_MS] old and the last failed try over [RETRY_MS]. */
        fun isDue(lastOk: Long, lastFailed: Long, now: Long) = now - lastOk > MAX_AGE_MS && now - lastFailed > RETRY_MS
    }
}
