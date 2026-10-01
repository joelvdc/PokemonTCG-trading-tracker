package com.poketrader.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where a card picked in search or the scanner goes. */
sealed interface CardTarget {
    data class TradeSide(val tradeId: Long, val side: String) : CardTarget
    data class Collection(val binderId: Long = Binder.UNSORTED) : CardTarget
    /** The Scan tab's list of cards waiting for a decision. */
    data object Scans : CardTarget
    data class ReplaceTradeItem(val itemId: Long) : CardTarget
    data class ReplaceCollectionItem(val itemId: Long) : CardTarget
    data class ReplaceScan(val itemId: Long) : CardTarget

    val isReplace get() = this is ReplaceTradeItem || this is ReplaceCollectionItem || this is ReplaceScan

    fun encode(): String = when (this) {
        is TradeSide -> "trade:$tradeId:$side"
        is Collection -> "collection:$binderId"
        Scans -> "scans"
        is ReplaceTradeItem -> "rtrade:$itemId"
        is ReplaceCollectionItem -> "rcoll:$itemId"
        is ReplaceScan -> "rscan:$itemId"
    }

    companion object {
        fun decode(s: String): CardTarget {
            val p = s.split(':')
            return when (p[0]) {
                "trade" -> TradeSide(p[1].toLong(), p[2])
                "scans" -> Scans
                "rtrade" -> ReplaceTradeItem(p[1].toLong())
                "rcoll" -> ReplaceCollectionItem(p[1].toLong())
                "rscan" -> ReplaceScan(p[1].toLong())
                else -> Collection(p.getOrNull(1)?.toLongOrNull() ?: Binder.UNSORTED)
            }
        }
    }
}

enum class AddedTo { TRADE, COLLECTION, SCANS }

/** Identifies the [copies] just added to a stack, so the scanner can undo or change them. */
data class AddResult(val itemId: Long, val addedTo: AddedTo, val copies: Int = 1)

data class ImportResult(val imported: Int, val notFound: Int)

/** A running CSV import: cards looked up so far, of [total] (0 while the file is read). */
data class CsvImportProgress(val done: Int, val total: Int)

/** Reverses a bulk action, e.g. from an Undo button. */
typealias UndoAction = suspend () -> Unit

class Repository(
    private val db: AppDatabase,
    val tcgdex: TcgdexApi,
    val prices: PriceGuideRepository,
    private val scope: CoroutineScope,
) {
    private val trades = db.tradeDao()
    private val coll = db.collectionDao()
    private val binders = db.binderDao()
    private val scans = db.scanDao()

    // ---- trades --------------------------------------------------------------------------

    suspend fun newTrade(): Long = trades.insert(Trade())

    suspend fun deleteEmptyDrafts() = trades.deleteEmptyDrafts()

    suspend fun deleteTrade(id: Long) = trades.delete(id)

    suspend fun setPartner(id: Long, partner: String) = trades.setPartner(id, partner)

    suspend fun setNotes(id: Long, notes: String) = trades.setNotes(id, notes)

    suspend fun snapshot(card: CardRef): PriceSet {
        val guide = prices.priceFor(card.cardmarketId, card.holoPrice)
        return if (guide != null && !guide.isEmpty) guide else PriceSet(trend = card.fallbackPrice)
    }

    suspend fun add(
        target: CardTarget, card: CardRef, language: String, qty: Int = 1, condition: String = "NM", hasJumbo: Boolean = false,
    ): AddResult? = when (target) {
        is CardTarget.TradeSide -> addToTrade(target.tradeId, target.side, card, language, qty, condition)
        is CardTarget.Collection -> addToCollection(card, condition, language, qty, target.binderId)
        CardTarget.Scans -> addToScans(card, condition, language, qty, hasJumbo)
        else -> null
    }

    suspend fun addToTrade(tradeId: Long, side: String, card: CardRef, language: String, qty: Int = 1, condition: String = "NM"): AddResult {
        val existing = trades.findSame(tradeId, side, card.cardId, card.dataLang, card.variantId, language)
        if (existing != null) {
            trades.updateItem(existing.copy(quantity = existing.quantity + qty))
            return AddResult(existing.id, AddedTo.TRADE, qty)
        }
        val id = trades.insertItem(
            TradeItem(tradeId = tradeId, side = side, card = card, condition = condition, language = language, quantity = qty, prices = snapshot(card))
        )
        return AddResult(id, AddedTo.TRADE, qty)
    }

    /** Takes back the copies [r] added (the rest of the stack stays). */
    suspend fun undoAdd(r: AddResult) {
        val n = r.copies
        when (r.addedTo) {
            AddedTo.TRADE -> {
                val item = trades.item(r.itemId) ?: return
                if (item.quantity > n) trades.updateItem(item.copy(quantity = item.quantity - n)) else trades.deleteItem(item.id)
            }
            AddedTo.COLLECTION -> {
                val item = coll.byId(r.itemId) ?: return
                if (item.quantity > n) coll.update(item.copy(quantity = item.quantity - n)) else coll.deleteById(item.id)
            }
            AddedTo.SCANS -> {
                val item = scans.byId(r.itemId) ?: return
                if (item.quantity > n) scans.update(item.copy(quantity = item.quantity - n)) else scans.delete(item.id)
            }
        }
    }

    /**
     * Changes what the scanner just added (tap on a scanned card): the copies [r] added are taken
     * back and [quantity] copies of [card] in that condition and language are added instead, so
     * they land in the right stack. Returns what was added now.
     */
    suspend fun changeAdded(
        r: AddResult, target: CardTarget, card: CardRef, condition: String, language: String, quantity: Int, hasJumbo: Boolean = false,
    ): AddResult? = db.withTransaction {
        undoAdd(r)
        add(target, card, language, quantity.coerceAtLeast(1), condition, hasJumbo)
    }

    /** Saves an edited item; re-reads its prices if the variant changed. */
    suspend fun updateTradeItem(updated: TradeItem) {
        val old = trades.item(updated.id) ?: return
        val fixed = if (old.card.variantId != updated.card.variantId || old.card.cardId != updated.card.cardId)
            updated.copy(prices = snapshot(updated.card)) else updated
        trades.updateItem(fixed)
    }

    suspend fun deleteTradeItem(id: Long) = trades.deleteItem(id)

    /** Undo for [deleteTradeItem]: puts the item back exactly as it was (if its trade still exists). */
    suspend fun restoreTradeItem(item: TradeItem) {
        if (trades.get(item.tradeId) != null && trades.item(item.id) == null) trades.insertItem(item)
    }

    /** Undo for [deleteTrade]: puts the trade and all its cards back, including whether it was applied. */
    suspend fun restoreTrade(t: TradeWithItems) = db.withTransaction {
        if (trades.get(t.trade.id) != null) return@withTransaction
        trades.insert(t.trade)
        t.items.forEach { trades.insertItem(it) }
    }

    suspend fun refreshTradePrices(tradeId: Long) {
        val t = trades.get(tradeId) ?: return
        for (item in t.items) trades.updateItem(item.copy(prices = snapshot(item.card)))
    }

    /** Swaps an item for another card/variant picked in search. */
    suspend fun replaceCard(target: CardTarget, card: CardRef) {
        when (target) {
            is CardTarget.ReplaceTradeItem -> {
                val item = trades.item(target.itemId) ?: return
                trades.updateItem(item.copy(card = card, prices = snapshot(card)))
            }
            is CardTarget.ReplaceCollectionItem -> {
                val item = coll.byId(target.itemId) ?: return
                updateCollectionItem(item.copy(card = card))
            }
            is CardTarget.ReplaceScan -> {
                val item = scans.byId(target.itemId) ?: return
                updateScan(item.copy(card = card))
            }
            else -> {}
        }
    }

    /**
     * Adds the cards you got to "My cards" (in [binderId]) and takes the cards you gave out of it,
     * from Unsorted before other binders. Returns how many given copies weren't found.
     */
    suspend fun applyTrade(tradeId: Long, binderId: Long = Binder.UNSORTED): Int = db.withTransaction {
        val t = trades.get(tradeId) ?: return@withTransaction 0
        if (t.trade.applied) return@withTransaction 0
        var missing = 0
        for (item in t.items) {
            if (item.side == Side.GET) {
                addToCollection(item.card, item.condition, item.language, item.quantity, binderId)
                trades.updateItem(item.copy(appliedDelta = item.quantity))
            } else {
                val r = removeFromCollection(item.card, item.condition, item.language, item.quantity)
                missing += item.quantity - r.removed
                trades.updateItem(item.copy(appliedDelta = -r.removed, appliedBinderId = r.fromBinder))
            }
        }
        trades.update(t.trade.copy(applied = true, appliedAt = System.currentTimeMillis(), binderId = binderId))
        missing
    }

    /** Reverses what [applyTrade] did, so the trade can be edited again. */
    suspend fun revertTrade(tradeId: Long) = db.withTransaction {
        val t = trades.get(tradeId) ?: return@withTransaction
        if (!t.trade.applied) return@withTransaction
        for (item in t.items) {
            when {
                item.appliedDelta > 0 ->
                    removeFromCollection(item.card, item.condition, item.language, item.appliedDelta, prefer = t.trade.binderId)
                item.appliedDelta < 0 ->
                    addToCollection(item.card, item.condition, item.language, -item.appliedDelta, existingBinder(item.appliedBinderId))
            }
            trades.updateItem(item.copy(appliedDelta = 0, appliedBinderId = Binder.UNSORTED))
        }
        trades.update(t.trade.copy(applied = false, appliedAt = null))
    }

    // ---- collection ----------------------------------------------------------------------

    suspend fun addToCollection(card: CardRef, condition: String, language: String, qty: Int, binderId: Long = Binder.UNSORTED): AddResult {
        val existing = coll.find(card.cardId, card.dataLang, card.variantId, condition, language, binderId)
        if (existing != null) {
            coll.update(existing.copy(quantity = existing.quantity + qty, card = card))
            return AddResult(existing.id, AddedTo.COLLECTION, qty)
        }
        val id = coll.insert(CollectionItem(card = card, condition = condition, language = language, quantity = qty, binderId = binderId))
        return AddResult(id, AddedTo.COLLECTION, qty)
    }

    private data class Removal(val removed: Int, val fromBinder: Long)

    /**
     * Removes up to [qty] copies of a card variant, taking stacks with the exact condition and
     * language first, and within those the [prefer] binder first.
     */
    private suspend fun removeFromCollection(
        card: CardRef, condition: String, language: String, qty: Int, prefer: Long = Binder.UNSORTED,
    ): Removal {
        var remaining = qty
        var from: Long? = null
        val candidates = coll.findAny(card.cardId, card.dataLang, card.variantId).sortedWith(
            compareBy({ if (it.condition == condition && it.language == language) 0 else 1 }, { if (it.binderId == prefer) 0 else 1 })
        )
        for (c in candidates) {
            if (remaining == 0) break
            val take = minOf(remaining, c.quantity)
            if (c.quantity - take <= 0) coll.deleteById(c.id) else coll.update(c.copy(quantity = c.quantity - take))
            remaining -= take
            if (from == null) from = c.binderId
        }
        return Removal(qty - remaining, from ?: prefer)
    }

    /** [id] if that binder still exists, else Unsorted. */
    private suspend fun existingBinder(id: Long): Long =
        if (id == Binder.UNSORTED || binders.get(id) != null) id else Binder.UNSORTED

    /** Saves an edited row, merging it into an existing row if it now has the same card/variant/condition/language/binder. */
    suspend fun updateCollectionItem(updated: CollectionItem) = db.withTransaction {
        val c = updated.card
        val clash = coll.find(c.cardId, c.dataLang, c.variantId, updated.condition, updated.language, updated.binderId)
        if (clash != null && clash.id != updated.id) {
            coll.update(clash.copy(quantity = clash.quantity + updated.quantity))
            coll.deleteById(updated.id)
        } else {
            coll.update(updated)
        }
    }

    /**
     * Saves the card screen: the edited stack, of which [move] copies go to [binderId] when that
     * differs from the stack's binder (all of them if [move] is the whole stack).
     */
    suspend fun saveCollectionEdit(updated: CollectionItem, binderId: Long, move: Int) = db.withTransaction {
        val n = move.coerceIn(0, updated.quantity)
        when {
            binderId == updated.binderId || n == 0 -> updateCollectionItem(updated)
            n >= updated.quantity -> updateCollectionItem(updated.copy(binderId = binderId))
            else -> {
                updateCollectionItem(updated.copy(quantity = updated.quantity - n))
                addToCollection(updated.card, updated.condition, updated.language, n, binderId)
            }
        }
    }

    suspend fun deleteCollectionItem(id: Long) = coll.deleteById(id)

    /** Undo for [deleteCollectionItem]; merges into a matching row if the same card was added again meanwhile. */
    suspend fun restoreCollectionItem(item: CollectionItem) = db.withTransaction {
        val c = item.card
        val clash = coll.find(c.cardId, c.dataLang, c.variantId, item.condition, item.language, item.binderId)
        when {
            clash != null -> coll.update(clash.copy(quantity = clash.quantity + item.quantity))
            coll.byId(item.id) == null -> coll.insert(item)
            else -> coll.insert(item.copy(id = 0))
        }
    }

    // ---- binders -------------------------------------------------------------------------

    /** Why [name] can't be used for a binder, or null if it can. */
    suspend fun binderNameProblem(name: String, except: Long? = null): String? {
        val n = name.trim()
        if (n.isEmpty()) return "Enter a name"
        if (n.equals(Binder.UNSORTED_NAME, ignoreCase = true)) return "“${Binder.UNSORTED_NAME}” is for cards outside binders"
        val other = binders.byName(n)
        return if (other != null && other.id != except) "There's already a binder called “${other.name}”" else null
    }

    /** Creates the binder, or returns the existing one with that name. */
    suspend fun createBinder(name: String): Long {
        val n = name.trim()
        return binders.byName(n)?.id ?: binders.insert(Binder(name = n))
    }

    /** The binder a [BinderChoice] points to, creating it first if it's new. */
    suspend fun resolve(choice: BinderChoice): Long = choice.newName?.let { createBinder(it) } ?: choice.binderId

    suspend fun renameBinder(id: Long, name: String) = binders.rename(id, name.trim())

    /**
     * Moves every card of binder [from] (or Unsorted) into [into], merging identical stacks, and
     * deletes [from] afterwards if [deleteSource] (Unsorted itself is never deleted).
     */
    suspend fun mergeBinder(from: Long, into: Long, deleteSource: Boolean) = db.withTransaction {
        if (from == into) return@withTransaction
        for (item in coll.inBinder(from)) updateCollectionItem(item.copy(binderId = into))
        if (deleteSource && from != Binder.UNSORTED) binders.delete(from)
    }

    /** Deletes a binder, moving its cards to Unsorted or, with [deleteCards], removing them. */
    suspend fun deleteBinder(id: Long, deleteCards: Boolean) = db.withTransaction {
        if (id == Binder.UNSORTED) return@withTransaction
        if (deleteCards) coll.deleteBinderCards(id) else mergeBinder(id, Binder.UNSORTED, deleteSource = false)
        binders.delete(id)
    }

    // ---- scanned cards (Scan tab) --------------------------------------------------------

    private suspend fun addToScans(card: CardRef, condition: String, language: String, qty: Int, hasJumbo: Boolean): AddResult {
        val existing = scans.find(card.cardId, card.dataLang, card.variantId, condition, language)
        if (existing != null) {
            scans.update(existing.copy(quantity = existing.quantity + qty, scannedAt = System.currentTimeMillis()))
            return AddResult(existing.id, AddedTo.SCANS, qty)
        }
        val id = scans.insert(ScannedCard(card = card, condition = condition, language = language, quantity = qty, hasJumbo = hasJumbo))
        return AddResult(id, AddedTo.SCANS, qty)
    }

    /** Saves an edited scan, merging it into an identical one if there is one. */
    suspend fun updateScan(updated: ScannedCard) = db.withTransaction {
        val c = updated.card
        val clash = scans.find(c.cardId, c.dataLang, c.variantId, updated.condition, updated.language)
        if (clash != null && clash.id != updated.id) {
            scans.update(clash.copy(quantity = clash.quantity + updated.quantity))
            scans.delete(updated.id)
        } else {
            scans.update(updated)
        }
    }

    suspend fun deleteScan(id: Long) = scans.delete(id)

    suspend fun restoreScans(items: List<ScannedCard>) = scans.restore(items)

    /** Removes scans from the list; the returned action puts them back. */
    suspend fun discardScans(ids: List<Long>): UndoAction {
        val items = scans.byIds(ids)
        scans.deleteMany(ids)
        return { scans.restore(items) }
    }

    /** Adds the scans to "My cards" in [binderId]; unless [keep], they leave the scan list. */
    suspend fun scansToCollection(ids: List<Long>, binderId: Long, keep: Boolean): UndoAction {
        val items = scans.byIds(ids)
        db.withTransaction {
            items.forEach { addToCollection(it.card, it.condition, it.language, it.quantity, binderId) }
            if (!keep) scans.deleteMany(ids)
        }
        return {
            db.withTransaction {
                items.forEach { removeFromCollection(it.card, it.condition, it.language, it.quantity, prefer = binderId) }
                if (!keep) scans.restore(items)
            }
        }
    }

    /** Adds the scans to a side of a trade ([tradeId] 0 starts a new trade). Returns the trade's id. */
    suspend fun scansToTrade(ids: List<Long>, tradeId: Long, side: String, keep: Boolean): Long = db.withTransaction {
        val id = if (tradeId == 0L) newTrade() else tradeId
        scans.byIds(ids).forEach { addToTrade(id, side, it.card, it.language, it.quantity, it.condition) }
        if (!keep) scans.deleteMany(ids)
        id
    }

    // ---- Cardmarket links ----------------------------------------------------------------

    /**
     * Re-checks the Cardmarket product of saved cards and updates the ones that changed (in "My
     * cards", open trades and the scan list; completed trades keep their prices but get the
     * corrected link). With [full], every international card is looked up again — needed to catch
     * links to a same-named but different card, which only the card's attacks reveal; otherwise
     * only cards without a link, or with one [catalog] names differently. Returns how many changed.
     */
    suspend fun repairSavedCards(catalog: CardmarketCatalog, full: Boolean): Int {
        val collection = coll.all()
        val allTrades = trades.all()
        val scanned = scans.all()
        val refs = collection.map { it.card } + allTrades.flatMap { t -> t.items.map { it.card } } + scanned.map { it.card }
        val suspicious = refs.filter { r ->
            r.cardmarketId == null || (!r.isJapanese && (full || catalog.looksWrong(r.name, r.cardmarketId)))
        }.map { it.cardId to it.dataLang }.distinct()
        var changed = 0
        for ((cardId, lang) in suspicious) {
            val card = runCatching { tcgdex.card(cardId, lang) }.getOrNull() ?: continue
            val byVariant = card.printings(lang).associateBy { it.variantId }
            fun fixed(old: CardRef): CardRef? {
                val p = byVariant[old.variantId] ?: return null
                if (p.cardmarketId == old.cardmarketId || p.cardmarketId == null) return null
                return old.copy(cardmarketId = p.cardmarketId, fallbackPrice = p.fallbackPrice ?: old.fallbackPrice)
            }
            db.withTransaction {
                collection.filter { it.card.cardId == cardId && it.card.dataLang == lang }.forEach { item ->
                    fixed(item.card)?.let { coll.byId(item.id)?.let { cur -> coll.update(cur.copy(card = it)); changed++ } }
                }
                allTrades.forEach { t ->
                    t.items.filter { it.card.cardId == cardId && it.card.dataLang == lang }.forEach { item ->
                        fixed(item.card)?.let { ref ->
                            trades.updateItem(if (t.trade.applied) item.copy(card = ref) else item.copy(card = ref, prices = snapshot(ref)))
                            changed++
                        }
                    }
                }
                scanned.filter { it.card.cardId == cardId && it.card.dataLang == lang }.forEach { item ->
                    fixed(item.card)?.let { scans.update(item.copy(card = it)); changed++ }
                }
            }
        }
        return changed
    }

    // ---- CSV -----------------------------------------------------------------------------

    private val collectionHeader = listOf(
        "Name", "Set", "Number", "Variant", "Quantity", "Condition", "Language", "Price EUR",
        "TCGdex ID", "Data language", "Variant ID", "Binder Name",
    )

    /**
     * Collection backup/export; the TCGdex columns let [importCollectionCsv] restore exact variants.
     * With [binderId], only that binder's cards (or Unsorted's) are exported.
     */
    suspend fun exportCollectionCsv(type: PriceType, binderId: Long? = null): String {
        val items = coll.all().filter { binderId == null || it.binderId == binderId }
        val binderNames = binders.all().associate { it.id to it.name }
        val priceMap = prices.pricesFor(items.mapNotNull { it.card.cardmarketId })
        val sb = StringBuilder()
        sb.appendLine(Csv.row(*collectionHeader.toTypedArray()))
        for (i in items) {
            val c = i.card
            val price = priceMap[c.cardmarketId]?.toSet(c.holoPrice)?.best(type) ?: c.fallbackPrice
            sb.appendLine(
                Csv.row(
                    c.name, c.setName, c.numberLabel, c.variantLabel, i.quantity, i.condition, i.language, price,
                    c.cardId, c.dataLang, c.variantId, binderNames[i.binderId] ?: "",
                )
            )
        }
        return sb.toString()
    }

    /**
     * Imports a CSV made by [exportCollectionCsv] (e.g. when moving to a new phone). Quantities are
     * added; rows with a "Binder Name" go into that binder (created if needed), others into
     * [defaultBinder]. Each card is looked up once; a failed lookup is retried once.
     */
    suspend fun importCollectionCsv(
        text: String,
        defaultBinder: Long = Binder.UNSORTED,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportResult {
        val rows = Csv.parse(text.removePrefix("﻿")).filter { r -> r.any { it.isNotBlank() } }
        if (rows.size < 2) return ImportResult(0, 0)
        val header = rows[0].map { it.trim().lowercase() }
        fun col(name: String) = header.indexOf(name.lowercase()).takeIf { it >= 0 }
        val iId = col("TCGdex ID") ?: return ImportResult(0, rows.size - 1)
        val iLang = col("Data language")
        val iVariant = col("Variant ID")
        val iQty = col("Quantity")
        val iCond = col("Condition")
        val iLanguage = col("Language")
        val iBinder = col("Binder Name")

        data class Line(val id: String, val dataLang: String, val qty: Int, val variant: String?, val cond: String?, val language: String?, val binder: String?)
        val lines = rows.drop(1).mapNotNull { r ->
            fun v(i: Int?) = i?.let { r.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() }
            val id = v(iId) ?: return@mapNotNull null
            Line(id, v(iLang) ?: "en", v(iQty)?.toIntOrNull() ?: 1, v(iVariant), v(iCond), v(iLanguage), v(iBinder))
        }
        val keys = lines.map { it.dataLang to it.id }.distinct()
        val cards = HashMap<Pair<String, String>, TcgCard?>()
        keys.forEachIndexed { n, key ->
            cards[key] = lookupWithRetry(key.second, key.first)
            onProgress(n + 1, keys.size)
        }
        var imported = 0
        var notFound = 0
        db.withTransaction {
            val binderIds = HashMap<String, Long>()
            for (l in lines) {
                val card = cards[l.dataLang to l.id]
                if (card == null) {
                    notFound += l.qty
                    continue
                }
                val printings = card.printings(l.dataLang)
                val ref = printings.firstOrNull { it.variantId == l.variant } ?: printings.first()
                val cond = l.cond?.uppercase()?.takeIf { c -> CONDITIONS.any { it.first == c } } ?: "NM"
                val binder = l.binder?.takeUnless { it.equals(Binder.UNSORTED_NAME, ignoreCase = true) }
                    ?.let { name -> binderIds.getOrPut(name.lowercase()) { createBinder(name) } }
                    ?: if (l.binder != null) Binder.UNSORTED else defaultBinder
                addToCollection(ref, cond, l.language?.uppercase() ?: if (l.dataLang == "ja") "JA" else "EN", l.qty, binder)
                imported += l.qty
            }
        }
        return ImportResult(imported, notFound)
    }

    /** One card from TCGdex; a network hiccup gets one more try before the card counts as not found. */
    private suspend fun lookupWithRetry(id: String, dataLang: String): TcgCard? {
        repeat(2) { attempt ->
            try {
                return tcgdex.card(id, dataLang)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == 0) delay(1_500)
            }
        }
        return null
    }

    private val _csvImport = MutableStateFlow<CsvImportProgress?>(null)

    /** The CSV import in progress, if any. */
    val csvImport: StateFlow<CsvImportProgress?> = _csvImport

    private val _csvImportResult = MutableStateFlow<String?>(null)

    /** How the last CSV import ended, until the screen has shown it ([consumeCsvImportResult]). */
    val csvImportResult: StateFlow<String?> = _csvImportResult

    fun consumeCsvImportResult() {
        _csvImportResult.value = null
    }

    /** Imports a CSV in the app scope, so it carries on if the user leaves the screen. False if one is already running. */
    fun startCsvImport(readText: suspend () -> String, defaultBinder: Long): Boolean {
        synchronized(this) {
            if (_csvImport.value != null) return false
            _csvImport.value = CsvImportProgress(0, 0)
        }
        scope.launch {
            _csvImportResult.value = try {
                val r = importCollectionCsv(readText(), defaultBinder) { done, total -> _csvImport.value = CsvImportProgress(done, total) }
                "Imported ${r.imported} card(s)" + if (r.notFound > 0) " · ${r.notFound} not found" else ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Import failed: ${e.message}"
            } finally {
                _csvImport.value = null
            }
        }
        return true
    }

    suspend fun exportTradesCsv(type: PriceType): String {
        // Trades are identified by when they were started; this format sorts correctly in spreadsheets.
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        val sb = StringBuilder()
        sb.appendLine(Csv.row("Trade started", "Partner", "Applied to collection", "Side", "Name", "Set", "Number", "Variant", "Condition", "Language", "Quantity", "Unit price EUR (${type.short})", "Custom price", "Line total EUR", "TCGdex ID"))
        for (t in trades.all()) {
            for (i in t.items) {
                sb.appendLine(
                    Csv.row(
                        df.format(Date(t.trade.createdAt)), t.trade.partner, if (t.trade.applied) "yes" else "no",
                        if (i.side == Side.GET) "received" else "given", i.card.name, i.card.setName, i.card.numberLabel,
                        i.card.variantLabel, i.condition, i.language, i.quantity,
                        i.unitPrice(type), i.customPrice, i.lineTotal(type), i.card.cardId,
                    )
                )
            }
        }
        return sb.toString()
    }
}
