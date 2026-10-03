package com.poketrader.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.poketrader.container
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Keeps Cardmarket data fresh: the price guide (daily, ~15 MB) and the card list (weekly, ~14 MB),
 * after which saved cards' Cardmarket links are re-checked. With automatic updates on, this runs
 * when the app opens, when the phone reconnects, and in the background every few hours — only
 * on Wi-Fi if the user chose so. "Update now" in Settings always runs.
 */
class DataUpdater(
    private val context: Context,
    private val settings: Settings,
    private val prices: PriceGuideRepository,
    private val catalog: CardmarketCatalog,
    private val repo: Repository,
    private val network: NetworkMonitor,
    private val history: ValueHistory,
) {
    private val lock = Mutex()

    /** Whether automatic updates may run right now. */
    fun allowedNow(): Boolean = settings.autoUpdate.value && (!settings.wifiOnly.value || network.onUnmeteredNetwork())

    /** Updates whatever is out of date, if automatic updates are allowed right now. */
    suspend fun autoUpdate() {
        if (allowedNow()) lock.withLock {
            if (prices.isStale) prices.refresh()
            if (catalog.isStale && catalog.refresh()) recheckAll()
        }
        recordValue()
    }

    /** Updates prices and the card list now, whatever the settings say. */
    suspend fun updateNow() {
        lock.withLock {
            prices.refresh()
            if (catalog.refresh()) recheckAll()
        }
        recordValue()
    }

    /** Saves today's collection value for the value chart. */
    private suspend fun recordValue() {
        runCatching { history.record() }
    }

    /** Looks up every saved card's Cardmarket link again against a fresh card list. */
    private suspend fun recheckAll() {
        runCatching { repo.repairSavedCards(catalog, full = true) }.onSuccess { settings.cardLinksVersion = CARD_LINKS_VERSION }
    }

    /**
     * After an app update that improved how links are found: re-checks every saved card once the
     * card list is there; until then at least fills in cards without a link.
     */
    suspend fun repairAfterUpgrade() {
        if (settings.cardLinksVersion >= CARD_LINKS_VERSION) return
        lock.withLock {
            if (settings.cardLinksVersion >= CARD_LINKS_VERSION) return@withLock
            if (catalog.hasList()) recheckAll() else runCatching { repo.repairSavedCards(catalog, full = false) }
        }
    }

    /** Sets up (or cancels) the background update to match the settings. */
    fun schedule() {
        val wm = WorkManager.getInstance(context)
        if (!settings.autoUpdate.value) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        private const val WORK_NAME = "cardmarket-update"

        /** Raise when a release improves how Cardmarket links are found, so saved cards get re-checked. */
        const val CARD_LINKS_VERSION = 2
    }
}

/** Background update; see [DataUpdater.schedule]. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val updater = applicationContext.container.updater
        updater.autoUpdate()
        updater.repairAfterUpgrade()
        return Result.success()
    }
}
