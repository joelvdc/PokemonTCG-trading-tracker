package com.poketrader

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poketrader.data.AppDatabase
import com.poketrader.data.Binder
import com.poketrader.data.CardRef
import com.poketrader.data.CardTarget
import com.poketrader.data.PriceGuideRepository
import com.poketrader.data.Repository
import com.poketrader.data.Settings
import com.poketrader.data.TcgdexApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Changing what the scanner added (tap on a scanned card), including the number of copies. */
@RunWith(AndroidJUnit4::class)
class ScanEditTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository

    private fun card(variant: String = "normal") = CardRef(
        cardId = "base1-46", dataLang = "en", name = "Charmander", setId = "base1", setName = "Base Set", localId = "46",
        setOfficial = 102, rarity = "Common", imageBase = null, variantId = variant, variantLabel = "Normal",
        cardmarketId = null, holoPrice = false, fallbackPrice = 2.6, firstEdition = false,
    )

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val http = OkHttpClient()
        repo = Repository(db, TcgdexApi(http), PriceGuideRepository(context, http, db, Settings(context)), CoroutineScope(Dispatchers.Default))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun stack() = db.collectionDao().all()

    @Test
    fun settingTwoCopiesSticks() = runBlocking {
        val target = CardTarget.Collection(Binder.UNSORTED)
        val first = repo.add(target, card(), "EN")!!
        assertEquals(1, first.copies)

        // Tap the scanned card, set 2 copies, save.
        val changed = repo.changeAdded(first, target, card(), "NM", "EN", 2)!!
        assertEquals(2, changed.copies)
        assertEquals(listOf(2), stack().map { it.quantity })

        // Opening it again shows 2 (the dialog starts from the entry's copies); setting 3 makes it 3, not 4.
        val again = repo.changeAdded(changed, target, card(), "NM", "EN", 3)!!
        assertEquals(listOf(3), stack().map { it.quantity })

        // Undo takes back all of them.
        repo.undoAdd(again)
        assertTrue(stack().isEmpty())
    }

    @Test
    fun changingOneScanLeavesEarlierCopiesAlone() = runBlocking {
        val target = CardTarget.Collection(Binder.UNSORTED)
        repo.add(target, card(), "EN", qty = 4)
        val scanned = repo.add(target, card(), "EN")!!
        assertEquals(listOf(5), stack().map { it.quantity })

        // The scanned copy was a German one, 2 of them: 4 English stay, 2 German are added.
        repo.changeAdded(scanned, target, card(), "NM", "DE", 2)
        assertEquals(mapOf("EN" to 4, "DE" to 2), stack().associate { it.language to it.quantity })
    }

    @Test
    fun worksForTheScanList() = runBlocking {
        val first = repo.add(CardTarget.Scans, card(), "EN")!!
        repo.changeAdded(first, CardTarget.Scans, card(), "NM", "EN", 2)
        assertEquals(listOf(2), db.scanDao().all().map { it.quantity })
    }
}
