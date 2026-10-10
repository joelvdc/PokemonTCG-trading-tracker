package com.poketrader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Ways to show the collection: big card pictures (as before 1.7), a list with small pictures, or one text line per card. */
enum class CollectionView(val label: String) {
    CARDS("Cards (big pictures)"),
    LIST("List"),
    COMPACT("Compact (text only)");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.name == key } ?: CARDS
    }
}

/** Light or dark colours. Since 1.9. */
enum class ThemeMode(val label: String) {
    SYSTEM("Same as the phone"),
    LIGHT("Light"),
    DARK("Dark");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.name == key } ?: SYSTEM
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(ThemeMode.fromKey(prefs.getString("themeMode", null)))
    val themeMode: StateFlow<ThemeMode> = _themeMode

    fun setThemeMode(v: ThemeMode) {
        _themeMode.value = v
        prefs.edit().putString("themeMode", v.name).apply()
    }

    private val _priceType = MutableStateFlow(PriceType.fromKey(prefs.getString("priceType", null)))
    val priceType: StateFlow<PriceType> = _priceType

    private val _currency = MutableStateFlow(AppCurrency.fromKey(prefs.getString("currency", null)))
    /** The currency prices are shown in. Since 1.15. */
    val currency: StateFlow<AppCurrency> = _currency

    fun setCurrency(c: AppCurrency) {
        _currency.value = c
        prefs.edit().putString("currency", c.name).apply()
    }

    private val _tolerance = MutableStateFlow(prefs.getInt("tolerancePct", 5))
    val tolerancePct: StateFlow<Int> = _tolerance

    private val _lastFetch = MutableStateFlow(prefs.getLong("lastPriceFetch", 0L))
    val lastPriceFetch: StateFlow<Long> = _lastFetch

    private val _guideDate = MutableStateFlow(prefs.getString("priceGuideDate", null))
    val priceGuideDate: StateFlow<String?> = _guideDate

    private val _catalogFetchedAt = MutableStateFlow(prefs.getLong("catalogFetchedAt", 0L))
    val catalogFetchedAt: StateFlow<Long> = _catalogFetchedAt

    private val _catalogDate = MutableStateFlow(prefs.getString("catalogDate", null))
    val catalogDate: StateFlow<String?> = _catalogDate

    /** Update prices and the card list by themselves (on opening the app and in the background). */
    private val _autoUpdate = MutableStateFlow(prefs.getBoolean("autoUpdate", true))
    val autoUpdate: StateFlow<Boolean> = _autoUpdate

    /** Automatic updates only on Wi-Fi (or another unmetered connection). */
    private val _wifiOnly = MutableStateFlow(prefs.getBoolean("wifiOnly", false))
    val wifiOnly: StateFlow<Boolean> = _wifiOnly

    /** How the collection shows the cards. Since 1.7. */
    private val _collectionView = MutableStateFlow(CollectionView.fromKey(prefs.getString("collectionView", null)))
    val collectionView: StateFlow<CollectionView> = _collectionView

    fun setCollectionView(v: CollectionView) {
        _collectionView.value = v
        prefs.edit().putString("collectionView", v.name).apply()
    }

    /** The collection's sort levels. Since 1.11. */
    private val _collectionSort = MutableStateFlow(SortSpec.decode(prefs.getString("collectionSort", null)))
    val collectionSort: StateFlow<SortSpec> = _collectionSort

    fun setCollectionSort(v: SortSpec) {
        _collectionSort.value = v
        prefs.edit().putString("collectionSort", v.encode()).apply()
    }

    /** The card page lists all your other copies of the card, not just the first few. Since 1.11. */
    private val _cardOthersOpen = MutableStateFlow(prefs.getBoolean("cardOthersOpen", false))
    val cardOthersOpen: StateFlow<Boolean> = _cardOthersOpen

    fun setCardOthersOpen(v: Boolean) {
        _cardOthersOpen.value = v
        prefs.edit().putBoolean("cardOthersOpen", v).apply()
    }

    /** Show a trade's two sides next to each other (give left, get right). Since 1.11. */
    private val _tradeColumns = MutableStateFlow(prefs.getBoolean("tradeColumns", false))
    val tradeColumns: StateFlow<Boolean> = _tradeColumns

    fun setTradeColumns(v: Boolean) {
        _tradeColumns.value = v
        prefs.edit().putBoolean("tradeColumns", v).apply()
    }

    /** How far saved cards' Cardmarket links have been checked; see [Repository.repairSavedCards]. */
    var cardLinksVersion: Int
        get() = prefs.getInt("cardLinksVersion", 0)
        set(v) = prefs.edit().putInt("cardLinksVersion", v).apply()

    fun setAutoUpdate(on: Boolean) {
        _autoUpdate.value = on
        prefs.edit().putBoolean("autoUpdate", on).apply()
    }

    fun setWifiOnly(on: Boolean) {
        _wifiOnly.value = on
        prefs.edit().putBoolean("wifiOnly", on).apply()
    }

    fun setCatalogFetched(createdAt: String?, at: Long) {
        _catalogFetchedAt.value = at
        _catalogDate.value = createdAt
        prefs.edit().putLong("catalogFetchedAt", at).putString("catalogDate", createdAt).apply()
    }

    fun setPriceType(t: PriceType) {
        _priceType.value = t
        prefs.edit().putString("priceType", t.key).apply()
    }

    fun setTolerance(pct: Int) {
        _tolerance.value = pct
        prefs.edit().putInt("tolerancePct", pct).apply()
    }

    fun setPriceGuideFetched(createdAt: String?, at: Long) {
        _lastFetch.value = at
        _guideDate.value = createdAt
        prefs.edit().putLong("lastPriceFetch", at).putString("priceGuideDate", createdAt).apply()
    }
}
