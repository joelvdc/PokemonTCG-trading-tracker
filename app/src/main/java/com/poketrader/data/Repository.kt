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
    /** The wishlist (cards you want). Since 1.9. */
    data object Wishlist : CardTarget

    val isReplace get() = this is ReplaceTradeItem || this is ReplaceCollectionItem || this is ReplaceScan

    fun encode(): String = when (this) {
        is TradeSide -> "trade:$tradeId:$side"
        is Collection -> "collection:$binderId"
        Scans -> "scans"
        is ReplaceTradeItem -> "rtrade:$itemId"
        is ReplaceCollectionItem -> "rcoll:$itemId"
        is ReplaceScan -> "rscan:$itemId"
        Wishlist -> "wishlist"
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
                "wishlist" -> Wishlist
                else -> Collection(p.getOrNull(1)?.toLongOrNull() ?: Binder.UNSORTED)
            }
        }
    }
}

enum class AddedTo { TRADE, COLLECTION, SCANS, WISHLIST }

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
    private val wishlist = db.wishlistDao()

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
        CardTarget.Wishlist -> addToWishlist(card, qty)
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
            AddedTo.WISHLIST -> {
                val item = wishlist.byId(r.itemId) ?: return
                if (item.quantity > n) wishlist.update(item.copy(quantity = item.quantity - n)) else wishlist.delete(item.id)
            }
        }
    }

    // ---- wishlist ------------------------------------------------------------------------

    /** Adds [qty] wanted copies: to the entry for that card (or that exact variant, if the entry wants only it). */
    suspend fun addToWishlist(card: CardRef, qty: Int = 1): AddResult {
        val same = wishlist.byCard(card.cardId, card.dataLang).firstOrNull { it.anyVariant || it.card.variantId == card.variantId }
        if (same != null) {
            wishlist.update(same.copy(quantity = same.quantity + qty))
            return AddResult(same.id, AddedTo.WISHLIST, qty)
        }
        val item = WishlistItem(card = card, quantity = qty)
        return AddResult(wishlist.insert(item.copy(ownedAtAdd = ownedFor(item, coll.all()))), AddedTo.WISHLIST, qty)
    }

    suspend fun updateWishlistItem(item: WishlistItem) = wishlist.update(item)

    suspend fun deleteWishlistItem(id: Long) = wishlist.delete(id)

    suspend fun restoreWishlistItem(item: WishlistItem) {
        if (wishlist.byId(item.id) == null) wishlist.insert(item)
    }

    /** Copies of the entry's card in [all]: any variant, or only that variant. */
    private fun ownedFor(w: WishlistItem, all: List<CollectionItem>) =
        all.filter { it.card.cardId == w.card.cardId && it.card.dataLang == w.card.dataLang && (w.anyVariant || it.card.variantId == w.card.variantId) }
            .sumOf { it.quantity }

    /** Each wishlist entry against the collection. */
    fun wishlistOwned(items: List<WishlistItem>, collection: List<CollectionItem>): Map<Long, WishlistOwned> =
        items.associate { w ->
            val owned = ownedFor(w, collection)
            w.id to WishlistOwned(owned, (owned - w.ownedAtAdd).coerceAtLeast(0))
        }

    /**
     * Ticks or unticks "Ordered, on its way" straight from the list ([orderedAt] null unticks). Read fresh,
     * so it keeps the row's current updatedAt and the sync trigger stamps the change. Since 1.20.
     */
    suspend fun setWishlistOrdered(id: Long, orderedAt: Long?) {
        val w = wishlist.byId(id) ?: return
        if (w.orderedAt != orderedAt) wishlist.update(w.copy(orderedAt = orderedAt))
    }

    /**
     * An undo for a change to the wishlist: the rows in [before] come back as they were (keeping each
     * row's current updatedAt, so the sync trigger stamps the change), and rows made since ([created]) go.
     */
    private fun wishUndo(before: List<WishlistItem>, created: List<Long> = emptyList()): UndoAction = {
        db.withTransaction {
            created.forEach { wishlist.delete(it) }
            val now = wishlist.byIds(before.map { it.id }).associateBy { it.id }
            for (w in before) {
                val cur = now[w.id]
                if (cur == null) wishlist.insert(w) else if (cur != w) wishlist.update(w.copy(updatedAt = cur.updatedAt))
            }
        }
    }

    /** Ticks (or with null unticks) "Ordered" on several wishlist cards; received ones stay as they are. Since 1.20. */
    suspend fun setWishlistOrdered(ids: Collection<Long>, orderedAt: Long?): Pair<Int, UndoAction> {
        val before = wishlist.byIds(ids.toList()).filter { it.receivedAt == null && (it.orderedAt == null) != (orderedAt == null) }
        db.withTransaction { before.forEach { wishlist.update(it.copy(orderedAt = orderedAt)) } }
        return before.size to wishUndo(before)
    }

    /**
     * Marks wishlist cards as received ([at]): they move to the Received section as a record. With
     * [toCollection], each card's copies are also added to the collection (Unsorted, near mint, its own
     * version); cards added offline, without a set yet, are only marked. Returns how many cards were
     * marked and an undo (which takes the added copies out again too). Since 1.20.
     */
    suspend fun receiveWishlist(ids: Collection<Long>, toCollection: Boolean, at: Long = System.currentTimeMillis()): Pair<Int, UndoAction> {
        val before = wishlist.byIds(ids.toList()).filter { it.receivedAt == null }
        val added = mutableListOf<Pair<Long, Int>>()
        db.withTransaction {
            for (w in before) {
                // Copies added here are marked so: moving the card to the collection later won't add them again.
                val adding = toCollection && !w.isPending && w.receivedFrom == null
                wishlist.update(w.copy(receivedAt = at, receivedFrom = if (adding) WishlistItem.ADDED else w.receivedFrom))
                if (adding) added += addToCollection(w.card, "NM", if (w.card.isJapanese) "JA" else "EN", w.quantity).itemId to w.quantity
            }
        }
        val undoWish = wishUndo(before)
        val undo: UndoAction = {
            undoWish()
            db.withTransaction {
                for ((id, n) in added) {
                    val item = coll.byId(id) ?: continue
                    if (item.quantity > n) coll.update(item.copy(quantity = item.quantity - n)) else coll.deleteById(item.id)
                }
            }
        }
        return before.size to undo
    }

    /**
     * Moves a received wishlist card into the collection (swiped right in Received): [qty] copies of [card]
     * in [condition] and [language] go into [binderId] (joining the same version's stack there, whose
     * purchase price becomes the average with [price], in [into] when the currencies differ), and the card
     * leaves the wishlist. Returns an undo for both. Since 1.20.
     */
    suspend fun wishToCollection(
        wishId: Long, card: CardRef, condition: String, language: String, qty: Int, binderId: Long,
        price: Double?, currency: String?, into: AppCurrency? = null,
    ): UndoAction {
        val before = wishlist.byIds(listOf(wishId))
        val undoCollection = collectionChange(setOf(card.cardId to card.dataLang)) {
            addToCollection(card, condition, language, qty, binderId, price, currency, into)
            wishlist.deleteMany(listOf(wishId))
        }
        val undoWish = wishUndo(before)
        return { undoCollection(); undoWish() }
    }

    /** What [wishToCollection] would join, for [joinCurrencies]. */
    fun wishJoin(card: CardRef, condition: String, language: String, binderId: Long, price: Double?, currency: String?) =
        listOf(CollectionItem(id = -1, card = card, condition = condition, language = language, quantity = 1, binderId = binderId, purchasePrice = price, purchaseCurrency = currency))

    /** Every card ticked "Ordered" and not received yet. Since 1.20. */
    suspend fun orderedWishlistIds(): List<Long> = wishlist.wanted().filter { it.orderedAt != null }.map { it.id }

    /** Puts received cards back on the wishlist (wanted again). Since 1.20. */
    suspend fun unreceiveWishlist(ids: Collection<Long>): Pair<Int, UndoAction> {
        val before = wishlist.byIds(ids.toList()).filter { it.receivedAt != null }
        db.withTransaction { before.forEach { wishlist.update(it.copy(receivedAt = null)) } }
        return before.size to wishUndo(before)
    }

    /** Deletes wishlist cards. Returns how many and an undo. Since 1.20. */
    suspend fun deleteWishlistItems(ids: Collection<Long>): Pair<Int, UndoAction> {
        val before = wishlist.byIds(ids.toList())
        wishlist.deleteMany(before.map { it.id })
        return before.size to wishUndo(before)
    }

    /**
     * The wanted cards you now own more copies of than when you added them (see [wishlistOwned]), with
     * how many of the wanted copies that covers. Since 1.20 (before, they were simply removed).
     */
    suspend fun ownedSinceAdding(): List<Pair<WishlistItem, Int>> {
        val wanted = wishlist.wanted()
        val owned = wishlistOwned(wanted, coll.all())
        return wanted.mapNotNull { w -> owned[w.id]?.gotSince?.takeIf { it > 0 }?.let { w to minOf(it, w.quantity) } }
    }

    /**
     * Marks the cards you now own as received ([ownedSinceAdding]); they're already in the collection, so
     * nothing is added there. Of a card you got only some of, those copies become a received entry of their
     * own and the rest stays wanted. Returns the copies marked and an undo. Since 1.20.
     */
    suspend fun receiveOwned(at: Long = System.currentTimeMillis()): Pair<Int, UndoAction> {
        val got = ownedSinceAdding()
        val created = mutableListOf<Long>()
        db.withTransaction {
            for ((w, n) in got) {
                if (n >= w.quantity) wishlist.update(w.copy(receivedAt = at, receivedFrom = w.receivedFrom ?: WishlistItem.OWNED))
                else {
                    wishlist.update(w.copy(quantity = w.quantity - n, ownedAtAdd = w.ownedAtAdd + n))
                    created += wishlist.insert(w.copy(id = 0, uid = null, updatedAt = 0, quantity = n, receivedAt = at, receivedFrom = WishlistItem.OWNED))
                }
            }
        }
        return got.sumOf { it.second } to wishUndo(got.map { it.first }, created)
    }

    /** Clears received cards: all of them, or those received before [before]. Returns how many and an undo. Since 1.20. */
    suspend fun clearReceived(before: Long = Long.MAX_VALUE): Pair<Int, UndoAction> {
        val gone = wishlist.receivedBefore(before)
        if (gone.isEmpty()) return 0 to {}
        wishlist.deleteMany(gone.map { it.id })
        return gone.size to wishUndo(gone)
    }

    /** Clears received cards older than [clear] allows (Settings); nothing when they're kept. Since 1.20. */
    suspend fun autoClearReceived(clear: ReceivedClear, now: Long = System.currentTimeMillis()): Int =
        clear.cutoff(now)?.let { clearReceived(it).first } ?: 0

    /**
     * Fills in wishlist cards added offline ([PendingCard]) once the phone is online: the TCGdex card of
     * that name (English prints) linked to the same Cardmarket product, else the newest printing of that
     * name; as "any version". [isDigital] tells Pokémon TCG Pocket sets, which are left out. Returns how
     * many were filled in. Since 1.20.
     */
    suspend fun completePendingWishlist(isDigital: (setId: String) -> Boolean): Int {
        var done = 0
        for (w in wishlist.pending()) {
            val ref = runCatching { findByProduct(w.card.name, w.card.cardmarketId, isDigital) }.getOrNull() ?: continue
            db.withTransaction {
                val cur = wishlist.byId(w.id) ?: return@withTransaction
                val filled = cur.copy(card = ref, anyVariant = true)
                wishlist.update(filled.copy(ownedAtAdd = ownedFor(filled, coll.all())))
            }
            done++
        }
        return done
    }

    /** The printing of a card named [name] whose Cardmarket product is [idProduct]; else the newest printing of that name. */
    private suspend fun findByProduct(name: String, idProduct: Int?, isDigital: (String) -> Boolean): CardRef? {
        val briefs = tcgdex.cardsNamed(name).filterNot { isDigital(it.setId) }
        if (briefs.isEmpty()) return null
        // Newest sets last in TCGdex's list; look from the newest, a limited number of cards.
        for (b in briefs.asReversed().take(30)) {
            val card = tcgdex.card(b.id, "en") ?: continue
            card.printings("en").firstOrNull { idProduct != null && it.cardmarketId == idProduct }?.let { return it }
        }
        return tcgdex.card(briefs.last().id, "en")?.defaultPrinting("en", preferHolo = false)
    }

    /** Adds a card picked from Cardmarket's product list (offline) to the wishlist. Since 1.20. */
    suspend fun addPendingToWishlist(product: CmProduct, qty: Int = 1): AddResult {
        val ref = PendingCard.ref(product.name, product.idProduct)
        val same = wishlist.byCard(ref.cardId, ref.dataLang).firstOrNull()
        if (same != null) {
            wishlist.update(same.copy(quantity = same.quantity + qty))
            return AddResult(same.id, AddedTo.WISHLIST, qty)
        }
        return AddResult(wishlist.insert(WishlistItem(card = ref, quantity = qty)), AddedTo.WISHLIST, qty)
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
     * Adds the cards you got to the collection (in [binderId]) and takes the cards you gave out of it,
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

    /**
     * Adds [qty] copies; with a price ([paid] in [paidCurrency]) they join a stack's average paid (in
     * [into] when the two prices are in different currencies, see [PurchasePrices.joined]).
     */
    suspend fun addToCollection(
        card: CardRef, condition: String, language: String, qty: Int, binderId: Long = Binder.UNSORTED,
        paid: Double? = null, paidCurrency: String? = null, into: AppCurrency? = null,
    ): AddResult {
        val existing = coll.find(card.cardId, card.dataLang, card.variantId, condition, language, binderId)
        if (existing != null) {
            val (price, cur) = PurchasePrices.joined(existing.quantity, existing.purchasePrice, existing.purchaseCurrency, qty, paid, paidCurrency, into)
            coll.update(existing.copy(quantity = existing.quantity + qty, card = card, purchasePrice = price, purchaseCurrency = cur))
            return AddResult(existing.id, AddedTo.COLLECTION, qty)
        }
        val id = coll.insert(
            CollectionItem(card = card, condition = condition, language = language, quantity = qty, binderId = binderId, purchasePrice = paid, purchaseCurrency = paidCurrency.takeIf { paid != null })
        )
        return AddResult(id, AddedTo.COLLECTION, qty)
    }

    /**
     * The currencies to choose between before [changed] stacks are saved: when one would join another stack
     * and both have a purchase price in different currencies. Empty when no choice is needed. Since 1.20.
     */
    suspend fun joinCurrencies(changed: List<CollectionItem>): List<AppCurrency> {
        val out = linkedSetOf<AppCurrency>()
        for (u in changed) {
            val c = u.card
            val clash = coll.find(c.cardId, c.dataLang, c.variantId, u.condition, u.language, u.binderId) ?: continue
            if (clash.id != u.id && PurchasePrices.clash(clash, u)) {
                out += PurchasePrices.currencyOf(clash.purchaseCurrency)
                out += PurchasePrices.currencyOf(u.purchaseCurrency)
            }
        }
        return AppCurrency.entries.filter { it in out }
    }

    /** [joinCurrencies] for moving all the cards of binder [from] into [into]. */
    suspend fun mergeCurrencies(from: Long, into: Long): List<AppCurrency> =
        if (from == into) emptyList() else joinCurrencies(coll.inBinder(from).map { it.copy(binderId = into) })

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

    /**
     * Saves an edited row, merging it into an existing row if it now has the same card/variant/condition/
     * language/binder (the average paid, in [into] when the prices are in different currencies).
     */
    suspend fun updateCollectionItem(updated: CollectionItem, into: AppCurrency? = null) = db.withTransaction {
        val c = updated.card
        val clash = coll.find(c.cardId, c.dataLang, c.variantId, updated.condition, updated.language, updated.binderId)
        if (clash != null && clash.id != updated.id) {
            val (price, cur) = PurchasePrices.joined(clash.quantity, clash.purchasePrice, clash.purchaseCurrency, updated.quantity, updated.purchasePrice, updated.purchaseCurrency, into)
            coll.update(clash.copy(quantity = clash.quantity + updated.quantity, purchasePrice = price, purchaseCurrency = cur, notes = clash.notes ?: updated.notes))
            coll.deleteById(updated.id)
        } else {
            coll.update(updated)
        }
    }

    /**
     * Saves the card screen: the edited stack, of which [move] copies go to [binderId] when that
     * differs from the stack's binder (all of them if [move] is the whole stack).
     */
    suspend fun saveCollectionEdit(updated: CollectionItem, binderId: Long, move: Int, into: AppCurrency? = null) = db.withTransaction {
        val n = move.coerceIn(0, updated.quantity)
        when {
            binderId == updated.binderId || n == 0 -> updateCollectionItem(updated, into)
            n >= updated.quantity -> updateCollectionItem(updated.copy(binderId = binderId), into)
            else -> {
                updateCollectionItem(updated.copy(quantity = updated.quantity - n), into)
                // The copies moved keep what was paid for them (since 1.20).
                addToCollection(updated.card, updated.condition, updated.language, n, binderId, updated.purchasePrice, updated.purchaseCurrency, into)
            }
        }
    }

    /** What [saveCollectionEdit] would join, for [joinCurrencies]. */
    fun editedStacks(updated: CollectionItem, binderId: Long, move: Int): List<CollectionItem> {
        val n = move.coerceIn(0, updated.quantity)
        return when {
            binderId == updated.binderId || n == 0 -> listOf(updated)
            n >= updated.quantity -> listOf(updated.copy(binderId = binderId))
            else -> listOf(updated, updated.copy(id = -1, quantity = n, binderId = binderId))
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

    /**
     * Runs [change] on collection stacks of the cards [keys] (card id and data language) and returns an
     * undo that puts every stack of those cards back as it was: stacks merged away come back, stacks made
     * go. Since 1.20.
     */
    private suspend fun collectionChange(keys: Set<Pair<String, String>>, change: suspend () -> Unit): UndoAction {
        fun CollectionItem.key() = card.cardId to card.dataLang
        val before = db.withTransaction {
            val rows = coll.all().filter { it.key() in keys }
            change()
            rows
        }
        val ids = before.map { it.id }.toSet()
        return {
            db.withTransaction {
                for (now in coll.all().filter { it.key() in keys && it.id !in ids }) coll.deleteById(now.id)
                for (b in before) {
                    val cur = coll.byId(b.id)
                    if (cur == null) coll.insert(b) else if (cur != b) coll.update(b.copy(updatedAt = cur.updatedAt))
                }
            }
        }
    }

    private suspend fun stacks(ids: Collection<Long>) = ids.mapNotNull { coll.byId(it) }
    private fun keysOf(items: List<CollectionItem>) = items.map { it.card.cardId to it.card.dataLang }.toSet()

    /** Moves whole stacks to [binderId] (merging with the same card there). Returns the copies moved and an undo. Since 1.20. */
    suspend fun moveStacks(ids: Collection<Long>, binderId: Long, into: AppCurrency? = null): Pair<Int, UndoAction> {
        val items = stacks(ids).filter { it.binderId != binderId }
        val undo = collectionChange(keysOf(items)) {
            items.forEach { updateCollectionItem(it.copy(binderId = binderId), into) }
        }
        return items.sumOf { it.quantity } to undo
    }

    /** Moves [n] copies of one stack to [binderId] (the rest stays). Since 1.20. */
    suspend fun moveCopies(id: Long, binderId: Long, n: Int, into: AppCurrency? = null): Pair<Int, UndoAction> {
        val item = coll.byId(id) ?: return 0 to {}
        if (item.binderId == binderId || n <= 0) return 0 to {}
        val undo = collectionChange(keysOf(listOf(item))) { saveCollectionEdit(item, binderId, n, into) }
        return minOf(n, item.quantity) to undo
    }

    /** What moving [ids] to [binderId] would join, for [joinCurrencies]. */
    suspend fun movedStacks(ids: Collection<Long>, binderId: Long): List<CollectionItem> =
        stacks(ids).filter { it.binderId != binderId }.map { it.copy(binderId = binderId) }

    /** A stack edit for several stacks: what's null stays as each stack has it. Since 1.20. */
    data class StackEdit(val condition: String? = null, val language: String? = null, val price: Double? = null, val currency: String? = null) {
        fun apply(i: CollectionItem) = i.copy(
            condition = condition ?: i.condition,
            language = language ?: i.language,
            purchasePrice = price ?: i.purchasePrice,
            purchaseCurrency = if (price != null) currency else i.purchaseCurrency,
        )
    }

    /**
     * Sets condition, language and/or purchase price (null leaves it as it is) on several stacks, merging
     * stacks that become the same. Returns the stacks changed and an undo. Since 1.20.
     */
    suspend fun editStacks(ids: Collection<Long>, edit: StackEdit, into: AppCurrency? = null): Pair<Int, UndoAction> {
        val changed = stacks(ids).map { it to edit.apply(it) }.filter { (a, b) -> a != b }
        val undo = collectionChange(keysOf(changed.map { it.first })) {
            // Read again each time: an earlier one may have merged into this stack.
            for ((a, _) in changed) coll.byId(a.id)?.let { cur -> updateCollectionItem(edit.apply(cur), into) }
        }
        return changed.size to undo
    }

    /** What [editStacks] would join, for [joinCurrencies]. */
    suspend fun editedStacks(ids: Collection<Long>, edit: StackEdit): List<CollectionItem> = stacks(ids).map(edit::apply)

    /** Called before several stacks are deleted at once (a restore point). Since 1.20. */
    var beforeBatchDelete: (suspend () -> Unit)? = null

    /** Deletes several stacks (after a restore point). Returns the copies deleted and an undo. Since 1.20. */
    suspend fun deleteStacks(ids: Collection<Long>): Pair<Int, UndoAction> {
        val items = stacks(ids)
        if (items.isEmpty()) return 0 to {}
        runCatching { beforeBatchDelete?.invoke() }
        val undo = collectionChange(keysOf(items)) { items.forEach { coll.deleteById(it.id) } }
        return items.sumOf { it.quantity } to undo
    }

    /** One copy more ([delta] 1) or less (-1; the last copy removes the stack), with an undo. Since 1.20. */
    suspend fun changeQuantity(id: Long, delta: Int): Pair<CollectionItem?, UndoAction> {
        val item = coll.byId(id) ?: return null to {}
        val undo = collectionChange(keysOf(listOf(item))) {
            val n = item.quantity + delta
            if (n <= 0) coll.deleteById(item.id) else coll.update(item.copy(quantity = n))
        }
        return item to undo
    }

    /**
     * Puts stacks on the "I give" side of trade [tradeId] (a new trade when null): [copies] of each, or
     * every copy. Returns the trade, the copies added and an undo (a new trade goes again). Since 1.20.
     */
    suspend fun stacksToTrade(ids: Collection<Long>, tradeId: Long?, copies: Int? = null): Triple<Long, Int, UndoAction> {
        val items = stacks(ids)
        val made = tradeId == null
        val id = tradeId ?: newTrade()
        val before = trades.get(id)?.items.orEmpty()
        var n = 0
        db.withTransaction {
            for (i in items) {
                val qty = copies?.coerceIn(1, i.quantity) ?: i.quantity
                addToTrade(id, Side.GIVE, i.card, i.language, qty, i.condition)
                n += qty
            }
        }
        val undo: UndoAction = {
            db.withTransaction {
                if (made) trades.delete(id) else {
                    val keep = before.associateBy { it.id }
                    for (t in trades.get(id)?.items.orEmpty()) {
                        val old = keep[t.id]
                        if (old == null) trades.deleteItem(t.id) else if (old.quantity != t.quantity) trades.updateItem(t.copy(quantity = old.quantity))
                    }
                }
            }
        }
        return Triple(id, n, undo)
    }

    /** The selected stacks as a plain list, "2× Pikachu (Base Set #58/102, Reverse Holo)" per line. Since 1.20. */
    fun stacksText(items: List<CollectionItem>): String = items.joinToString("\n") { i ->
        val extra = listOfNotNull("${i.card.setName} #${i.card.numberLabel}", i.card.variantLabel.takeIf { it.isNotBlank() && it != "Normal" })
        "${i.quantity}× ${i.card.name} (${extra.joinToString(", ")})"
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
    suspend fun mergeBinder(from: Long, into: Long, deleteSource: Boolean, currency: AppCurrency? = null) = db.withTransaction {
        if (from == into) return@withTransaction
        for (item in coll.inBinder(from)) updateCollectionItem(item.copy(binderId = into), currency)
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

    /** Adds the scans to the collection in [binderId]; unless [keep], they leave the scan list. */
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
        "TCGdex ID", "Data language", "Variant ID", "Binder Name", "Purchase price", "Notes", "Purchase currency",
    )

    /**
     * Collection backup/export; the TCGdex columns let [importCollectionCsv] restore exact variants.
     * With [binderId], only that binder's cards (or Unsorted's) are exported.
     */
    suspend fun exportCollectionCsv(type: PriceType, binderId: Long? = null, ids: Set<Long>? = null): String {
        val items = coll.all().filter { (binderId == null || it.binderId == binderId) && (ids == null || it.id in ids) }
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
                    c.cardId, c.dataLang, c.variantId, binderNames[i.binderId] ?: "", i.purchasePrice, i.notes ?: "",
                    // Kept as typed (since 1.20); files from before have no such column and are in euros.
                    if (i.purchasePrice != null) i.purchaseCurrency ?: "EUR" else "",
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
        val iPaid = col("Purchase price")
        val iNotes = col("Notes")
        val iPaidCurrency = col("Purchase currency")

        data class Line(
            val id: String, val dataLang: String, val qty: Int, val variant: String?, val cond: String?, val language: String?, val binder: String?,
            val paid: Double? = null, val notes: String? = null, val paidCurrency: String? = null,
        )
        val lines = rows.drop(1).mapNotNull { r ->
            fun v(i: Int?) = i?.let { r.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() }
            val id = v(iId) ?: return@mapNotNull null
            Line(
                id, v(iLang) ?: "en", v(iQty)?.toIntOrNull() ?: 1, v(iVariant), v(iCond), v(iLanguage), v(iBinder),
                v(iPaid)?.replace(',', '.')?.toDoubleOrNull(), v(iNotes),
                v(iPaidCurrency)?.uppercase()?.takeIf { c -> c != "EUR" && AppCurrency.entries.any { it.name == c } },
            )
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
                val added = addToCollection(ref, cond, l.language?.uppercase() ?: if (l.dataLang == "ja") "JA" else "EN", l.qty, binder)
                // Notes and purchase price fill in what the stack doesn't have yet.
                if (l.paid != null || l.notes != null) coll.byId(added.itemId)?.let { s ->
                    val price = if (s.purchasePrice != null) s.purchasePrice to s.purchaseCurrency else l.paid to l.paidCurrency
                    coll.update(s.copy(purchasePrice = price.first, purchaseCurrency = price.second, notes = s.notes ?: l.notes))
                }
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
    /** Called before a CSV import changes anything (a restore point). Since 1.20. */
    var beforeCsvImport: (suspend () -> Unit)? = null

    fun startCsvImport(readText: suspend () -> String, defaultBinder: Long): Boolean {
        synchronized(this) {
            if (_csvImport.value != null) return false
            _csvImport.value = CsvImportProgress(0, 0)
        }
        scope.launch {
            _csvImportResult.value = try {
                beforeCsvImport?.invoke()
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
        sb.appendLine(Csv.row("Trade started", "Partner", "Applied to collection", "Side", "Name", "Set", "Number", "Variant", "Condition", "Language", "Quantity", "Unit price EUR (${type.short})", "Custom price", "Line total EUR", "TCGdex ID", "Custom price currency"))
        for (t in trades.all()) {
            for (i in t.items) {
                sb.appendLine(
                    Csv.row(
                        df.format(Date(t.trade.createdAt)), t.trade.partner, if (t.trade.applied) "yes" else "no",
                        if (i.side == Side.GET) "received" else "given", i.card.name, i.card.setName, i.card.numberLabel,
                        i.card.variantLabel, i.condition, i.language, i.quantity,
                        i.unitPrice(type), i.customPrice, i.lineTotal(type), i.card.cardId,
                        if (i.customPrice != null) i.customCurrency ?: "EUR" else "",
                    )
                )
            }
        }
        return sb.toString()
    }
}
