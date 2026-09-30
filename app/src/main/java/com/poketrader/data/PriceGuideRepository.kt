package com.poketrader.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

sealed interface PriceUpdateState {
    data object Idle : PriceUpdateState
    data class Running(val message: String) : PriceUpdateState
    data class Failed(val message: String) : PriceUpdateState
}

/**
 * Downloads Cardmarket's public daily price guide for Pokémon (all products, keyed by Cardmarket id)
 * and stores it locally. New sets appear here automatically — no app update needed.
 */
class PriceGuideRepository(
    private val context: Context,
    private val http: OkHttpClient,
    private val db: AppDatabase,
    private val settings: Settings,
) {
    companion object {
        const val URL = "https://downloads.s3.cardmarket.com/productCatalog/priceGuide/price_guide_6.json"
        private const val MAX_AGE_MS = 20L * 60 * 60 * 1000
    }

    private val dao = db.priceDao()
    private val lock = Mutex()
    private val _state = MutableStateFlow<PriceUpdateState>(PriceUpdateState.Idle)
    val state: StateFlow<PriceUpdateState> = _state
    val count = dao.count()

    val isStale get() = System.currentTimeMillis() - settings.lastPriceFetch.value > MAX_AGE_MS

    suspend fun refreshIfStale() {
        if (isStale) refresh()
    }

    suspend fun refresh(): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val tmp = File(context.cacheDir, "price_guide.json")
            try {
                // Mobile connections drop or switch networks mid-download; retry a few times.
                var attempt = 1
                while (true) {
                    try {
                        download(tmp)
                        break
                    } catch (e: IOException) {
                        if (attempt >= 3) throw e
                        _state.value = PriceUpdateState.Running("Connection lost, retrying…")
                        delay(2000L * attempt)
                        attempt++
                    }
                }
                _state.value = PriceUpdateState.Running("Saving prices…")
                val createdAt = import(tmp)
                settings.setPriceGuideFetched(createdAt, System.currentTimeMillis())
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
        _state.value = PriceUpdateState.Running("Downloading Cardmarket prices…")
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
                            _state.value = PriceUpdateState.Running("Downloading Cardmarket prices… $mb$of")
                        }
                    }
                }
            }
        }
    }

    /** Replaces the price table with the file's contents in one transaction. Returns the guide's createdAt. */
    private suspend fun import(file: File): String? {
        var createdAt: String? = null
        db.withTransaction {
            dao.clear()
            JsonReader(InputStreamReader(file.inputStream().buffered(), Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "createdAt" -> createdAt = reader.nextString()
                        "priceGuides" -> {
                            val batch = ArrayList<PriceEntity>(2000)
                            reader.beginArray()
                            while (reader.hasNext()) {
                                readEntry(reader)?.let { batch += it }
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

    private fun readEntry(r: JsonReader): PriceEntity? {
        var id: Int? = null
        val v = HashMap<String, Double?>(16)
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            if (r.peek() == JsonToken.NULL) {
                r.nextNull()
                continue
            }
            when (name) {
                "idProduct" -> id = r.nextInt()
                "avg", "low", "trend", "avg1", "avg7", "avg30",
                "avg-holo", "low-holo", "trend-holo", "avg1-holo", "avg7-holo", "avg30-holo" ->
                    v[name] = if (r.peek() == JsonToken.NUMBER) r.nextDouble().takeIf { it > 0 } else { r.skipValue(); null }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return id?.let {
            PriceEntity(
                idProduct = it,
                avg = v["avg"], low = v["low"], trend = v["trend"],
                avg1 = v["avg1"], avg7 = v["avg7"], avg30 = v["avg30"],
                avgHolo = v["avg-holo"], lowHolo = v["low-holo"], trendHolo = v["trend-holo"],
                avg1Holo = v["avg1-holo"], avg7Holo = v["avg7-holo"], avg30Holo = v["avg30-holo"],
            )
        }
    }

    suspend fun priceFor(cardmarketId: Int?, holo: Boolean): PriceSet? =
        cardmarketId?.let { dao.get(it) }?.toSet(holo)

    suspend fun pricesFor(ids: Collection<Int>): Map<Int, PriceEntity> =
        ids.distinct().chunked(500).flatMap { dao.getMany(it) }.associateBy { it.idProduct }
}
