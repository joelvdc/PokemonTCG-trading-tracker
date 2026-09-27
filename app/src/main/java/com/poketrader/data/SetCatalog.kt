package com.poketrader.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The list of sets per data language ("en", "ja"), cached on disk and refreshed weekly — or at once
 * when a card from an unknown (newly released) set shows up. The list is in release order.
 */
class SetCatalog(context: Context, private val api: TcgdexApi) {
    private val dir = File(context.filesDir, "sets").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val memory = HashMap<String, List<TcgSetBrief>>()
    private val refreshedThisRun = HashSet<String>()
    private val details = HashMap<String, TcgSetDetail?>()

    /** Sets of Pokémon TCG Pocket (a phone game): its cards don't exist on paper, so they're hidden. */
    @Volatile
    private var digitalIds: Set<String> = emptySet()
    private var digitalLoaded = false

    fun isDigital(lang: String, setId: String) = lang == "en" && (setId in digitalIds || POCKET_ID.matches(setId))

    private suspend fun loadDigital() {
        if (digitalLoaded) return
        digitalLoaded = true
        runCatching { api.serieSetIds("tcgp", "en") }.getOrNull()?.let { digitalIds = it.toSet() }
    }

    suspend fun sets(lang: String): List<TcgSetBrief> = lock.withLock {
        if (lang == "en") loadDigital()
        memory[lang]?.let { return@withLock it }
        val file = File(dir, "$lang.json")
        val cached = withContext(Dispatchers.IO) {
            if (file.exists()) runCatching { json.decodeFromString(ListSerializer(TcgSetBrief.serializer()), file.readText()) }.getOrNull() else null
        }
        val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > 7L * 24 * 3600 * 1000
        val list = (if (cached == null || stale) fetch(lang) ?: cached.orEmpty() else cached)
            .filter { !isDigital(lang, it.id) }
        memory[lang] = list
        list
    }

    private suspend fun fetch(lang: String): List<TcgSetBrief>? = try {
        val list = api.sets(lang)
        if (list.isNotEmpty()) {
            withContext(Dispatchers.IO) { File(dir, "$lang.json").writeText(json.encodeToString(ListSerializer(TcgSetBrief.serializer()), list)) }
        }
        list.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    /** Release-order position of a set (higher = newer); unknown sets count as newest. */
    suspend fun order(lang: String, setId: String): Int {
        val list = sets(lang)
        val i = list.indexOfFirst { it.id.equals(setId, ignoreCase = true) }
        if (i < 0) refreshOnce(lang)
        return if (i < 0) Int.MAX_VALUE else i
    }

    suspend fun find(lang: String, setId: String): TcgSetBrief? =
        sets(lang).firstOrNull { it.id.equals(setId, ignoreCase = true) } ?: run {
            if (refreshOnce(lang)) sets(lang).firstOrNull { it.id.equals(setId, ignoreCase = true) } else null
        }

    /** Sets whose printed card count ("/198") matches [count], newest first. */
    suspend fun withCount(lang: String, count: Int): List<TcgSetBrief> =
        sets(lang).filter { it.cardCount.official == count || it.cardCount.total == count }.asReversed()

    /** Official set abbreviation ("SVI", "PAL"…), fetched per set on demand. */
    suspend fun abbreviation(lang: String, setId: String): String? {
        val key = "$lang/$setId"
        val detail = lock.withLock { if (details.containsKey(key)) details[key] else null }
            ?: runCatching { api.setDetail(setId, lang) }.getOrNull().also { d -> lock.withLock { details[key] = d } }
        return detail?.abbreviation?.official
    }

    companion object {
        // Fallback when offline: Pocket set ids look like "A1", "A2b", "B1a", "P-A".
        private val POCKET_ID = Regex("""^(A\d+[a-z]?|B\d+[a-z]?|P-[AB])$""")
    }

    /** Re-downloads the list once per app run (a newly released set). Returns true if it did. */
    private suspend fun refreshOnce(lang: String): Boolean {
        val go = lock.withLock { refreshedThisRun.add(lang) }
        if (!go) return false
        val list = fetch(lang)?.filter { !isDigital(lang, it.id) } ?: return false
        lock.withLock { memory[lang] = list }
        return true
    }
}
