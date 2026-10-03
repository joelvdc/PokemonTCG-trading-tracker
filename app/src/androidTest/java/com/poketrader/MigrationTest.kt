package com.poketrader

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poketrader.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Each database update, checked against the schemas Room exports (app/schemas): the migrated
 * database must be exactly what a fresh install creates, and the cards must survive. Schemas are
 * exported since version 3 (app 1.6–1.8).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun from3to4KeepsMyCards() {
        helper.createDatabase(dbName, 3).apply {
            execSQL(
                "INSERT INTO collection (id, condition, language, quantity, addedAt, binderId, cardId, dataLang, name, setId, setName, localId, " +
                    "setOfficial, rarity, imageBase, variantId, variantLabel, cardmarketId, holoPrice, fallbackPrice, firstEdition) " +
                    "VALUES (1, 'NM', 'EN', 2, 1, 0, 'base1-4', 'en', 'Charizard', 'base1', 'Base Set', '4', 102, 'Rare Holo', NULL, " +
                    "'holo', 'Holo', 273699, 0, 300.0, 0)"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 4, true, AppDatabase.MIGRATION_3_4)
        db.query("SELECT name, quantity, notes, purchasePrice FROM collection").use { c ->
            c.moveToFirst()
            assertEquals("Charizard", c.getString(0))
            assertEquals(2, c.getInt(1))
            assertTrue(c.isNull(2) && c.isNull(3))
        }
        db.query("SELECT COUNT(*) FROM wishlist").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
    }
}
