package com.poketrader.data

import androidx.room.withTransaction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where a card picked in search or the scanner goes. */
sealed interface CardTarget {
    data class TradeSide(val tradeId: Long, val side: String) : CardTarget
    data object Collection : CardTarget
    data class ReplaceTradeItem(val itemId: Long) : CardTarget
    data class ReplaceCollectionItem(val itemId: Long) : CardTarget

    val isReplace get() = this is ReplaceTradeItem || this is ReplaceCollectionItem

    fun encode(): String = when (this) {
        is TradeSide -> "trade:$tradeId:$side"
        Collection -> "collection"
        is ReplaceTradeItem -> "rtrade:$itemId"
        is ReplaceCollectionItem -> "rcoll:$itemId"
    }

    companion object {
        fun decode(s: String): CardTarget {
            val p = s.split(':')
            return when (p[0]) {
                "trade" -> TradeSide(p[1].toLong(), p[2])
                "rtrade" -> ReplaceTradeItem(p[1].toLong())
                "rcoll" -> ReplaceCollectionItem(p[1].toLong())
                else -> Collection
            }
        }
    }
}

/** Identifies one added copy so the scanner can undo it. */
data class AddResult(val itemId: Long, val inTrade: Boolean)

data class ImportResult(val imported: Int, val notFound: Int)

class Repository(
    private val db: AppDatabase,
    val tcgdex: TcgdexApi,
    val prices: PriceGuideRepository,
) {
    private val trades = db.tradeDao()
    private val coll = db.collectionDao()

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

    suspend fun add(target: CardTarget, card: CardRef, language: String, qty: Int = 1, condition: String = "NM"): AddResult? = when (target) {
        is CardTarget.TradeSide -> addToTrade(target.tradeId, target.side, card, language, qty, condition)
        CardTarget.Collection -> addToCollection(card, condition, language, qty)
        else -> null
    }

    suspend fun addToTrade(tradeId: Long, side: String, card: CardRef, language: String, qty: Int = 1, condition: String = "NM"): AddResult {
        val existing = trades.findSame(tradeId, side, card.cardId, card.dataLang, card.variantId, language)
        if (existing != null) {
            trades.updateItem(existing.copy(quantity = existing.quantity + qty))
            return AddResult(existing.id, true)
        }
        val id = trades.insertItem(
            TradeItem(tradeId = tradeId, side = side, card = card, condition = condition, language = language, quantity = qty, prices = snapshot(card))
        )
        return AddResult(id, true)
    }

    suspend fun undoAdd(r: AddResult) {
        if (r.inTrade) {
            val item = trades.item(r.itemId) ?: return
            if (item.quantity > 1) trades.updateItem(item.copy(quantity = item.quantity - 1)) else trades.deleteItem(item.id)
        } else {
            val item = coll.byId(r.itemId) ?: return
            if (item.quantity > 1) coll.update(item.copy(quantity = item.quantity - 1)) else coll.deleteById(item.id)
        }
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
            else -> {}
        }
    }

    /**
     * Adds received cards to the collection and removes given cards from it.
     * Returns how many given copies weren't found in the collection.
     */
    suspend fun applyTrade(tradeId: Long): Int = db.withTransaction {
        val t = trades.get(tradeId) ?: return@withTransaction 0
        if (t.trade.applied) return@withTransaction 0
        var missing = 0
        for (item in t.items) {
            if (item.side == Side.GET) {
                addToCollection(item.card, item.condition, item.language, item.quantity)
                trades.updateItem(item.copy(appliedDelta = item.quantity))
            } else {
                val removed = removeFromCollection(item.card, item.condition, item.language, item.quantity)
                missing += item.quantity - removed
                trades.updateItem(item.copy(appliedDelta = -removed))
            }
        }
        trades.update(t.trade.copy(applied = true, appliedAt = System.currentTimeMillis()))
        missing
    }

    /** Reverses exactly what [applyTrade] did, so the trade can be edited again. */
    suspend fun revertTrade(tradeId: Long) = db.withTransaction {
        val t = trades.get(tradeId) ?: return@withTransaction
        if (!t.trade.applied) return@withTransaction
        for (item in t.items) {
            when {
                item.appliedDelta > 0 -> removeFromCollection(item.card, item.condition, item.language, item.appliedDelta)
                item.appliedDelta < 0 -> addToCollection(item.card, item.condition, item.language, -item.appliedDelta)
            }
            trades.updateItem(item.copy(appliedDelta = 0))
        }
        trades.update(t.trade.copy(applied = false, appliedAt = null))
    }

    // ---- collection ----------------------------------------------------------------------

    suspend fun addToCollection(card: CardRef, condition: String, language: String, qty: Int): AddResult {
        val existing = coll.find(card.cardId, card.dataLang, card.variantId, condition, language)
        if (existing != null) {
            coll.update(existing.copy(quantity = existing.quantity + qty, card = card))
            return AddResult(existing.id, false)
        }
        val id = coll.insert(CollectionItem(card = card, condition = condition, language = language, quantity = qty))
        return AddResult(id, false)
    }

    /** Removes up to [qty] copies, preferring the exact condition/language. Returns copies removed. */
    private suspend fun removeFromCollection(card: CardRef, condition: String, language: String, qty: Int): Int {
        var remaining = qty
        val exact = coll.find(card.cardId, card.dataLang, card.variantId, condition, language)
        val candidates = listOfNotNull(exact) + coll.findAny(card.cardId, card.dataLang, card.variantId).filter { it.id != exact?.id }
        for (c in candidates) {
            if (remaining == 0) break
            val take = minOf(remaining, c.quantity)
            if (c.quantity - take <= 0) coll.deleteById(c.id) else coll.update(c.copy(quantity = c.quantity - take))
            remaining -= take
        }
        return qty - remaining
    }

    /** Saves an edited row, merging it into an existing row if it now has the same card/variant/condition/language. */
    suspend fun updateCollectionItem(updated: CollectionItem) = db.withTransaction {
        val c = updated.card
        val clash = coll.find(c.cardId, c.dataLang, c.variantId, updated.condition, updated.language)
        if (clash != null && clash.id != updated.id) {
            coll.update(clash.copy(quantity = clash.quantity + updated.quantity))
            coll.deleteById(updated.id)
        } else {
            coll.update(updated)
        }
    }

    suspend fun deleteCollectionItem(id: Long) = coll.deleteById(id)

    /** Undo for [deleteCollectionItem]; merges into a matching row if the same card was added again meanwhile. */
    suspend fun restoreCollectionItem(item: CollectionItem) = db.withTransaction {
        val c = item.card
        val clash = coll.find(c.cardId, c.dataLang, c.variantId, item.condition, item.language)
        when {
            clash != null -> coll.update(clash.copy(quantity = clash.quantity + item.quantity))
            coll.byId(item.id) == null -> coll.insert(item)
            else -> coll.insert(item.copy(id = 0))
        }
    }

    // ---- CSV -----------------------------------------------------------------------------

    private val collectionHeader = listOf(
        "Name", "Set", "Number", "Variant", "Quantity", "Condition", "Language", "Price EUR",
        "TCGdex ID", "Data language", "Variant ID",
    )

    /** Collection backup/export; the last three columns let [importCollectionCsv] restore exact variants. */
    suspend fun exportCollectionCsv(type: PriceType): String {
        val items = coll.all()
        val priceMap = prices.pricesFor(items.mapNotNull { it.card.cardmarketId })
        val sb = StringBuilder()
        sb.appendLine(Csv.row(*collectionHeader.toTypedArray()))
        for (i in items) {
            val c = i.card
            val price = priceMap[c.cardmarketId]?.toSet(c.holoPrice)?.best(type) ?: c.fallbackPrice
            sb.appendLine(
                Csv.row(
                    c.name, c.setName, c.numberLabel, c.variantLabel, i.quantity, i.condition, i.language, price,
                    c.cardId, c.dataLang, c.variantId,
                )
            )
        }
        return sb.toString()
    }

    /** Imports a CSV made by [exportCollectionCsv] (e.g. when moving to a new phone). Quantities are added. */
    suspend fun importCollectionCsv(text: String): ImportResult {
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
        var imported = 0
        var notFound = 0
        val cache = HashMap<String, TcgCard?>()
        for (r in rows.drop(1)) {
            fun v(i: Int?) = i?.let { r.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() }
            val id = v(iId) ?: continue
            val dataLang = v(iLang) ?: "en"
            val qty = v(iQty)?.toIntOrNull() ?: 1
            val card = cache.getOrPut("$dataLang/$id") { runCatching { tcgdex.card(id, dataLang) }.getOrNull() }
            if (card == null) {
                notFound += qty
                continue
            }
            val printings = card.printings(dataLang)
            val ref = printings.firstOrNull { it.variantId == v(iVariant) } ?: printings.first()
            val cond = v(iCond)?.uppercase()?.takeIf { c -> CONDITIONS.any { it.first == c } } ?: "NM"
            addToCollection(ref, cond, v(iLanguage)?.uppercase() ?: if (dataLang == "ja") "JA" else "EN", qty)
            imported += qty
        }
        return ImportResult(imported, notFound)
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
