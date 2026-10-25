package com.poketrader

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.poketrader.data.AppCurrency
import com.poketrader.data.AppDatabase
import com.poketrader.data.BackupFile
import com.poketrader.data.BackupInfo
import com.poketrader.data.BackupPlace
import com.poketrader.data.BackupRestore
import com.poketrader.data.BackupRetention
import com.poketrader.data.Binder
import com.poketrader.data.BinderOrder
import com.poketrader.data.CardRef
import com.poketrader.data.CollectionItem
import com.poketrader.data.CollectionRow
import com.poketrader.data.DisplayCurrency
import com.poketrader.data.FirstSync
import com.poketrader.data.Money
import com.poketrader.data.PendingCard
import com.poketrader.data.PriceAlerts
import com.poketrader.data.PriceEntity
import com.poketrader.data.PriceSourceStore
import com.poketrader.data.PriceType
import com.poketrader.data.ProfitLoss
import com.poketrader.data.purchaseEur
import com.poketrader.data.PurchasePrices
import com.poketrader.data.ReceivedClear
import com.poketrader.data.Repository
import com.poketrader.data.SortField
import com.poketrader.data.SortLevel
import com.poketrader.data.SortSpec
import com.poketrader.data.SyncData
import com.poketrader.data.SyncDeletion
import com.poketrader.data.SyncException
import com.poketrader.data.SyncFile
import com.poketrader.data.SyncMerge
import com.poketrader.data.SyncSchema
import com.poketrader.data.SyncStack
import com.poketrader.data.SyncStore
import com.poketrader.data.Settings
import com.poketrader.data.TcgdexApi
import com.poketrader.data.ValueHistory
import com.poketrader.data.WishlistItem
import com.poketrader.data.PriceGuideRepository
import com.poketrader.ui.MoneyInput
import com.poketrader.ui.wishSections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 1.20: features brought over from MTG Trader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class Release120Test {
    private fun card(id: String, name: String = id, variant: String = "normal", cm: Int? = null) =
        CardRef(id, "en", name, "base1", "Base Set", id.substringAfter('-'), 102, "Rare", null, variant, variant.replaceFirstChar { it.uppercase() }, cm, false, null, false)

    private fun price(id: Int, trend: Double?, avg30: Double?, low: Double? = trend) =
        PriceEntity(id, null, low, trend, null, null, avg30, null, null, null, null, null, null)

    private fun row(c: CardRef, qty: Int = 1, p: PriceEntity? = null, paid: Double? = null, cur: String? = null, id: Long = 0) =
        CollectionRow(CollectionItem(id = id, card = c, quantity = qty, purchasePrice = paid, purchaseCurrency = cur), p)

    @After
    fun reset() {
        Money.display = DisplayCurrency()
        Money.rates = null
    }

    // ---- 1. Sort by price change --------------------------------------------------------------------

    @Test
    fun sortsByChangeWithCardsWithoutAverageLast() {
        val up = row(card("base1-1", "Up", cm = 1), p = price(1, 12.0, 10.0), id = 1)
        val down = row(card("base1-2", "Down", cm = 2), p = price(2, 5.0, 10.0), id = 2)
        val none = row(card("base1-3", "None", cm = 3), p = price(3, 5.0, null), id = 3)
        val bigMoney = row(card("base1-4", "Big", cm = 4), p = price(4, 150.0, 100.0), id = 4)
        val rows = listOf(none, down, up, bigMoney)
        val pct = rows.sortedWith(SortSpec(listOf(SortLevel(SortField.CHANGE_PCT))).comparator(PriceType.TREND)).map { it.item.card.name }
        assertEquals(listOf("Big", "Up", "Down", "None"), pct)
        val fallers = rows.sortedWith(SortSpec(listOf(SortLevel(SortField.CHANGE_PCT, reversed = true))).comparator(PriceType.TREND)).map { it.item.card.name }
        assertEquals(listOf("Down", "Up", "Big", "None"), fallers)
        val money = rows.sortedWith(SortSpec(listOf(SortLevel(SortField.CHANGE_MONEY))).comparator(PriceType.TREND)).map { it.item.card.name }
        assertEquals(listOf("Big", "Up", "Down", "None"), money)
    }

    @Test
    fun risingLatelyIsPerCardNotPerStack() {
        // 10 copies rising €1 each vs. one copy rising €5: the single card rises more per card.
        val many = row(card("base1-1", "Many", cm = 1), qty = 10, p = price(1, 3.0, 2.0))
        val one = row(card("base1-2", "One", cm = 2), qty = 1, p = price(2, 10.0, 5.0))
        val moves = ValueHistory.biggestMoves(listOf(many, one), up = true)
        assertEquals(listOf("One", "Many"), moves.map { it.row.item.card.name })
        assertEquals(5.0, moves[0].change, 1e-9)
    }

    @Test
    fun aCardInTwoBindersShowsOnceAmongTheMoves() {
        val c = card("base1-1", "Split", cm = 1)
        val p = price(1, 12.0, 10.0)
        val inA = CollectionRow(CollectionItem(id = 1, card = c, quantity = 1, binderId = 1), p)
        val inB = CollectionRow(CollectionItem(id = 2, card = c, quantity = 3, binderId = 2), p)
        val moves = ValueHistory.biggestMoves(listOf(inA, inB), up = true)
        assertEquals(listOf(2L), moves.map { it.row.item.id })
    }

    // ---- 6. Paid and worth --------------------------------------------------------------------------

    @Test
    fun paidAndWorthCountsOnlyCardsWithAPrice() {
        val a = row(card("base1-1", "A", cm = 1), qty = 2, p = price(1, 10.0, 10.0), paid = 4.0)
        val b = row(card("base1-2", "B", cm = 2), qty = 1, p = price(2, 2.0, 2.0), paid = 5.0)
        val c = row(card("base1-3", "C", cm = 3), qty = 3, p = price(3, 1.0, 1.0))
        val p = ProfitLoss.of(listOf(a, b, c), PriceType.TREND)
        assertEquals(13.0, p.paid, 1e-9)
        assertEquals(22.0, p.worth, 1e-9)
        assertEquals(3, p.counted)
        assertEquals(6, p.copies)
        assertEquals(listOf("A"), p.best.map { it.row.item.card.name })
        assertEquals(listOf("B"), p.worst.map { it.row.item.card.name })
    }

    @Test
    fun paidInKronerCountsAtTodaysRate() {
        Money.rates = com.poketrader.data.ExchangeRates("2026-10-25", mapOf("DKK" to 7.5, "USD" to 1.1))
        val a = row(card("base1-1", "A", cm = 1), p = price(1, 10.0, 10.0), paid = 75.0, cur = "DKK")
        assertEquals(10.0, a.item.purchaseEur!!, 1e-9)
        assertEquals(0.0, ProfitLoss.of(listOf(a), PriceType.TREND).gain, 1e-9)
    }

    // ---- 13. Prices kept in their currency ----------------------------------------------------------

    @Test
    fun currencyFieldKeepsTheAmountAndConvertsBackExactly() {
        Money.display = DisplayCurrency(AppCurrency.DKK, 7.46)
        Money.rates = com.poketrader.data.ExchangeRates("2026-10-25", mapOf("DKK" to 7.46, "USD" to 1.08))
        val saved = MoneyInput.of(300.0, "DKK")
        assertEquals(AppCurrency.DKK, saved.currency)
        assertEquals(300.0, saved.amount!!, 1e-9)
        assertEquals("DKK", saved.code)
        saved.switchTo(AppCurrency.EUR)
        assertEquals(300.0 / 7.46, saved.amount!!, 1e-9)
        assertNull(saved.code)
        saved.switchTo(AppCurrency.DKK)
        assertEquals(300.0, saved.amount!!, 1e-9)
        // An empty field just changes its currency.
        val empty = MoneyInput.of(null, null)
        empty.switchTo(AppCurrency.USD)
        assertNull(empty.amount)
        assertEquals("USD", empty.code)
    }

    @Test
    fun joiningStacksAveragesAndAsksForTheCurrencyWhenTheyDiffer() {
        Money.rates = com.poketrader.data.ExchangeRates("2026-10-25", mapOf("DKK" to 7.5, "USD" to 1.25))
        // Same currency: weighted average.
        assertEquals(3.0 to "DKK", PurchasePrices.joined(2, 2.0, "DKK", 2, 4.0, "DKK"))
        // One side without a price: the other's.
        assertEquals(75.0 to "DKK", PurchasePrices.joined(1, null, null, 1, 75.0, "DKK"))
        assertEquals(10.0 to null as String?, PurchasePrices.joined(1, 10.0, null, 3, null, null))
        // Different currencies: in the one chosen. 1 at €10 and 1 at 150 kr. (€20) → €15 or 112.50 kr.
        assertEquals(15.0 to null as String?, PurchasePrices.joined(1, 10.0, null, 1, 150.0, "DKK", AppCurrency.EUR))
        assertEquals(112.5 to "DKK", PurchasePrices.joined(1, 10.0, null, 1, 150.0, "DKK", AppCurrency.DKK))
        assertTrue(PurchasePrices.clash(CollectionItem(card = card("x-1"), quantity = 1, purchasePrice = 1.0), CollectionItem(card = card("x-1"), quantity = 1, purchasePrice = 1.0, purchaseCurrency = "USD")))
        assertFalse(PurchasePrices.clash(CollectionItem(card = card("x-1"), quantity = 1, purchasePrice = 1.0), CollectionItem(card = card("x-1"), quantity = 1)))
    }

    // ---- 5. Arrange binders -------------------------------------------------------------------------

    @Test
    fun binderOrderKeepsWhatWasArrangedAndAddsNewBindersLast() {
        val a = Binder(id = 1, name = "Alpha", uid = "a")
        val b = Binder(id = 2, name = "Beta", uid = "b")
        val c = Binder(id = 3, name = "Charlie", uid = "c")
        assertEquals(listOf("unsorted", "wishlist", "b:a", "b:b"), BinderOrder.arrange(listOf(b, a), emptyList()))
        val arranged = listOf("b:b", "wishlist", "unsorted", "b:a", "b:gone")
        assertEquals(listOf("b:b", "wishlist", "unsorted", "b:a", "b:c"), BinderOrder.arrange(listOf(a, b, c), arranged))
        assertEquals(listOf("b", "a", "c"), BinderOrder.move(listOf("a", "b", "c"), 1, -1))
        assertEquals(listOf("a", "b", "c"), BinderOrder.move(listOf("a", "b", "c"), 0, -1))
    }

    // ---- 8. Wishlist sections and price alerts ------------------------------------------------------

    @Test
    fun wishlistSectionsSplitToBuyOrderedAndReceived() {
        val items = listOf(
            WishlistItem(id = 1, card = card("base1-1", "A")),
            WishlistItem(id = 2, card = card("base1-2", "B"), orderedAt = 5),
            WishlistItem(id = 3, card = card("base1-3", "C"), receivedAt = 10),
            WishlistItem(id = 4, card = card("base1-4", "D"), receivedAt = 20),
        ).associateBy { it.id }
        val rows = items.values.map { row(it.card, id = it.id) }
        val on = wishSections(rows, { items[it] }, sections = true)
        assertEquals(listOf("tobuy", "ordered", "received"), on.map { it.key })
        assertEquals(listOf("D", "C"), on.last().rows.map { it.item.card.name })
        val off = wishSections(rows, { items[it] }, sections = false)
        assertEquals(listOf(null, "Received"), off.map { it.title })
        assertEquals(listOf("A", "B"), off.first().rows.map { it.item.card.name })
    }

    @Test
    fun alertNotifiesOnceUntilThePriceRisesAgain() {
        val first = PriceAlerts.step(level = 10.0, best = 9.0, alertedLevel = null)
        assertTrue(first.notify)
        val again = PriceAlerts.step(10.0, 8.0, first.alertedLevel)
        assertFalse(again.notify)
        val above = PriceAlerts.step(10.0, 11.0, again.alertedLevel)
        assertFalse(above.notify)
        assertNull(above.alertedLevel)
        assertTrue(PriceAlerts.step(10.0, 10.0, above.alertedLevel).notify)
        // No price known: nothing changes.
        assertEquals(PriceAlerts.Step(false, 10.0), PriceAlerts.step(10.0, null, 10.0))
        // A level kept in kroner: its value in euros moves daily, the key (as typed) doesn't.
        val kr = PriceAlerts.step(level = 40.2, best = 40.0, alertedLevel = 300.0, key = 300.0)
        assertFalse(kr.notify)
    }

    @Test
    fun receivedCardsClearAfterTheChosenMonths() {
        val now = 400L * 24 * 60 * 60 * 1000
        assertNull(ReceivedClear.NEVER.cutoff(now))
        assertEquals(now - 90L * 24 * 60 * 60 * 1000, ReceivedClear.M3.cutoff(now))
    }

    // ---- 10. Wishlist offline -----------------------------------------------------------------------

    @Test
    fun offlineCardKeepsCardmarketsProductAndAttacks() {
        val ref = PendingCard.ref("Nidoran [M] [Horn Hazard]", 273111)
        assertTrue(PendingCard.isPending(ref.cardId))
        assertEquals("Nidoran ♂", ref.name)
        assertEquals(273111, ref.cardmarketId)
        assertEquals("Horn Hazard", ref.variantLabel)
        assertFalse(PendingCard.isPending("base1-4"))
    }

    // ---- 11, 12. Sync and backups -------------------------------------------------------------------

    private fun stack(uid: String, id: String, qty: Int, at: Long, binder: String? = null) =
        SyncStack(CollectionItem(card = card(id), quantity = qty, addedAt = 1, uid = uid, updatedAt = at), binder)

    @Test
    fun syncKeepsTheNewestChangeAndDeletionsWin() {
        val now = 1_800_000_000_000L
        // Recent times: deletions older than half a year are forgotten.
        val t = now - 1_000_000
        val local = SyncData(collection = listOf(stack("s1", "base1-1", 2, at = t + 100), stack("s2", "base1-2", 1, at = t + 100)))
        val remote = SyncData(
            collection = listOf(stack("s1", "base1-1", 5, at = t + 200), stack("s3", "base1-3", 1, at = t + 50)),
            deletions = listOf(SyncDeletion("s2", t + 150)),
        )
        val m = SyncMerge.merge(local.sorted(), remote.sorted(), now)
        assertEquals(mapOf("s1" to 5, "s3" to 1), m.collection.associate { it.item.uid to it.item.quantity })
        assertEquals(listOf("s2"), m.deletions.map { it.uid })
        // Both phones end up the same; syncing again changes nothing.
        assertEquals(m, SyncMerge.merge(m, m, now))
        assertEquals(m, SyncMerge.merge(remote.sorted(), local.sorted(), now).copy(prefs = m.prefs))
    }

    @Test
    fun syncFoldsBindersWithTheSameNameAndMovesTheirCards() {
        val now = 1_800_000_000_000L
        val a = SyncData(binders = listOf(Binder(name = "Trade", createdAt = 1, uid = "ba", updatedAt = 10)), collection = listOf(stack("s1", "base1-1", 1, 10, "ba")))
        val b = SyncData(binders = listOf(Binder(name = "trade ", createdAt = 2, uid = "bb", updatedAt = 10)), collection = listOf(stack("s2", "base1-2", 1, 10, "bb")))
        val m = SyncMerge.merge(a.sorted(), b.sorted(), now)
        assertEquals(listOf("ba"), m.binders.map { it.uid })
        assertEquals(setOf("ba"), m.collection.map { it.binder }.toSet())
    }

    @Test
    fun firstSyncFromThisPhoneDeletesWhatOnlyNextcloudHad() {
        val now = 1_800_000_000_000L
        val local = SyncData(collection = listOf(stack("s1", "base1-1", 1, 10)))
        val remote = SyncData(collection = listOf(stack("s9", "base1-9", 1, 10)))
        val m = SyncMerge.merge(local.sorted(), remote.sorted(), now, FirstSync.USE_LOCAL)
        assertEquals(listOf("s1"), m.collection.map { it.item.uid })
        assertEquals(listOf("s9"), m.deletions.map { it.uid })
    }

    @Test
    fun syncFileRoundTripsAndRefusesOtherAppsFiles() {
        val data = SyncData(collection = listOf(stack("s1", "base1-1", 3, 10))).sorted()
        val back = SyncFile.decode(SyncFile.encode(SyncFile(writtenAt = 5, writtenBy = "Pixel", data = data)))
        assertEquals(data, back.data)
        val mtg = SyncFile.encode(SyncFile(app = "MTG Trader", data = data))
        assertTrue(runCatching { SyncFile.decode(mtg) }.exceptionOrNull() is SyncException)
        val backup = BackupFile.decode(BackupFile.encode(BackupFile(createdAt = 5, data = data)))
        assertEquals(data, backup.data)
    }

    @Test
    fun backupsKeepTheLatestTenAndOnePerWeek() {
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        val all = (0 until 40).map { BackupInfo(BackupPlace.PHONE, "b$it", "b$it", now - it * day, "Daily backup", false, 1) } +
            BackupInfo(BackupPlace.PHONE, "kept", "kept", now - 90 * day, "Made by hand", true, 1)
        val gone = BackupRetention.toDelete(all, now).map { it.name }.toSet()
        assertTrue("the newest ten stay", (0 until 10).none { "b$it" in gone })
        assertFalse("made by hand stays", "kept" in gone)
        val kept = all.size - gone.size
        assertTrue(kept in 11..20)
    }

    @Test
    fun restoringDeletesWhatTheBackupDoesNotHave() {
        val now = 1_800_000_000_000L
        val current = SyncData(collection = listOf(stack("s1", "base1-1", 1, 10), stack("s2", "base1-2", 1, 10))).sorted()
        val backup = SyncData(collection = listOf(stack("s1", "base1-1", 4, 5))).sorted()
        val t = BackupRestore.target(current, backup, now)
        assertEquals(listOf("s1" to 4), t.collection.map { it.item.uid to it.item.quantity })
        assertEquals(now, t.collection.single().item.updatedAt)
        assertEquals(listOf("s2"), t.deletions.map { it.uid })
    }

    // ---- On a database: stacks, the wishlist, change tracking ---------------------------------------

    private fun db(): AppDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) = SyncSchema.install(db)
        })
        .allowMainThreadQueries()
        .build()

    private fun repo(db: AppDatabase): Repository {
        val context = RuntimeEnvironment.getApplication()
        val settings = Settings(context)
        val http = okhttp3.OkHttpClient()
        return Repository(db, TcgdexApi(http), PriceGuideRepository(context, http, db, settings), CoroutineScope(Dispatchers.Default))
    }

    @Test
    fun movingSelectedCardsJoinsStacksAndUndoPutsThemBack() = runBlocking {
        val db = db()
        try {
            val r = repo(db)
            val b = r.createBinder("Main")
            val a = r.addToCollection(card("base1-4"), "NM", "EN", 2, Binder.UNSORTED, paid = 10.0, paidCurrency = null).itemId
            r.addToCollection(card("base1-4"), "NM", "EN", 2, b, paid = 150.0, paidCurrency = "DKK")
            // Different currencies: the screen asks; here kroner was picked.
            assertEquals(listOf(AppCurrency.EUR, AppCurrency.DKK), r.joinCurrencies(r.movedStacks(listOf(a), b)))
            Money.rates = com.poketrader.data.ExchangeRates("2026-10-25", mapOf("DKK" to 7.5))
            val (n, undo) = r.moveStacks(listOf(a), b, AppCurrency.DKK)
            assertEquals(2, n)
            val joined = db.collectionDao().all().single()
            assertEquals(4, joined.quantity)
            assertEquals(112.5, joined.purchasePrice!!, 1e-9)
            assertEquals("DKK", joined.purchaseCurrency)
            undo()
            assertEquals(setOf(2), db.collectionDao().all().map { it.quantity }.toSet())
            assertEquals(2, db.collectionDao().all().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun editingSelectedStacksAndOneCopyMoreOrLess() = runBlocking {
        val db = db()
        try {
            val r = repo(db)
            val x = r.addToCollection(card("base1-1"), "NM", "EN", 1).itemId
            val y = r.addToCollection(card("base1-2"), "LP", "DE", 3).itemId
            val (n, undo) = r.editStacks(listOf(x, y), Repository.StackEdit(condition = "EX", price = 20.0, currency = "USD"))
            assertEquals(2, n)
            val all = db.collectionDao().all().associateBy { it.id }
            assertEquals("EX", all[x]!!.condition)
            assertEquals("DE", all[y]!!.language)
            assertEquals("USD", all[y]!!.purchaseCurrency)
            undo()
            assertEquals("LP", db.collectionDao().byId(y)!!.condition)
            val (_, undoMinus) = r.changeQuantity(x, -1)
            assertNull(db.collectionDao().byId(x))
            undoMinus()
            assertNotNull(db.collectionDao().byId(x))
        } finally {
            db.close()
        }
    }

    @Test
    fun wishlistCardsGoToBuyOrderedReceivedAndIntoTheCollection() = runBlocking {
        val db = db()
        try {
            val r = repo(db)
            val w = r.addToWishlist(card("base1-4", "Charizard")).itemId
            r.setWishlistOrdered(listOf(w), 1_000)
            val (n, undo) = r.receiveWishlist(listOf(w), toCollection = true, at = 2_000)
            assertEquals(1, n)
            val got = db.wishlistDao().byId(w)!!
            assertEquals(2_000L, got.receivedAt)
            assertEquals(WishlistItem.ADDED, got.receivedFrom)
            assertEquals(1, db.collectionDao().all().single().quantity)
            // Received cards no longer count as wanted: adding the card again makes a new entry.
            assertTrue(r.addToWishlist(card("base1-4", "Charizard")).itemId != w)
            undo()
            assertNull(db.wishlistDao().byId(w)!!.receivedAt)
            assertTrue(db.collectionDao().all().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun changesAreTrackedForSyncAndAppliedBackWithoutLoops() = runBlocking {
        val db = db()
        try {
            val r = repo(db)
            val settings = Settings(RuntimeEnvironment.getApplication())
            val store = SyncStore(db, settings)
            val id = r.addToCollection(card("base1-4"), "NM", "EN", 1).itemId
            val item = db.collectionDao().byId(id)!!
            assertEquals(32, item.uid!!.length)
            assertTrue(item.updatedAt > 0)
            db.collectionDao().deleteById(id)
            assertEquals(listOf(item.uid), db.syncDao().deletions().map { it.uid })
            // What another phone sent comes in without being counted as an edit here.
            val (current, stamp) = store.snapshot()
            val incoming = current.copy(collection = listOf(stack("remote1", "base1-9", 2, at = 5)), deletions = emptyList())
            assertTrue(store.apply(current, incoming, stamp))
            assertEquals(stamp, store.lastChange())
            val again = store.snapshot().first
            assertEquals(listOf("remote1" to 2), again.collection.map { it.item.uid to it.item.quantity })
            assertEquals(5L, again.collection.single().item.updatedAt)
        } finally {
            db.close()
        }
    }

    @Test
    fun csvBackupKeepsThePurchaseCurrency() = runBlocking {
        val db = db()
        try {
            val r = repo(db)
            val id = r.addToCollection(card("base1-4"), "NM", "EN", 1, paid = 75.0, paidCurrency = "DKK").itemId
            assertNotNull(db.collectionDao().byId(id))
            val csv = r.exportCollectionCsv(PriceType.TREND)
            val header = csv.lines().first()
            assertTrue(header.endsWith("Purchase currency"))
            assertTrue(csv.lines()[1].endsWith(",DKK"))
        } finally {
            db.close()
        }
    }
}
