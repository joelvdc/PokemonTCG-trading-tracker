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

    private val _priceSource = MutableStateFlow(PriceSource.fromKey(prefs.getString("priceSource", null)))
    /** Where the price of a card comes from. Since 1.16. */
    val priceSource: StateFlow<PriceSource> = _priceSource

    fun setPriceSource(s: PriceSource) {
        _priceSource.value = s
        prefs.edit().putString("priceSource", s.key).stamp().apply()
    }

    private val _currency = MutableStateFlow(AppCurrency.fromKey(prefs.getString("currency", null)))
    /** The currency prices are shown in. Since 1.15. */
    val currency: StateFlow<AppCurrency> = _currency

    fun setCurrency(c: AppCurrency) {
        _currency.value = c
        prefs.edit().putString("currency", c.name).stamp().apply()
    }

    /** The currency last chosen in a price field (see [com.poketrader.ui.TypedCurrency]); kept per phone. Since 1.20. */
    var typedCurrency: String?
        get() = prefs.getString("typedCurrency", null)
        set(v) = prefs.edit().putString("typedCurrency", v).apply()

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
        prefs.edit().putString("collectionView", v.name).stamp().apply()
    }

    /** The collection's sort levels. Since 1.11. */
    private val _collectionSort = MutableStateFlow(SortSpec.decode(prefs.getString("collectionSort", null)))
    val collectionSort: StateFlow<SortSpec> = _collectionSort

    fun setCollectionSort(v: SortSpec) {
        _collectionSort.value = v
        prefs.edit().putString("collectionSort", v.encode()).stamp().apply()
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
        prefs.edit().putBoolean("tradeColumns", v).stamp().apply()
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
        prefs.edit().putString("priceType", t.key).stamp().apply()
    }

    fun setTolerance(pct: Int) {
        _tolerance.value = pct
        prefs.edit().putInt("tolerancePct", pct).stamp().apply()
    }


    // ---- since 1.20 ----------------------------------------------------------------------

    private val _wishSections = MutableStateFlow(prefs.getBoolean("wishSections", true))
    /** The wishlist shows To buy, Ordered and Received as sections (else one list, ordered cards last). Since 1.20. */
    val wishSections: StateFlow<Boolean> = _wishSections

    fun setWishSections(on: Boolean) {
        _wishSections.value = on
        prefs.edit().putBoolean("wishSections", on).stamp().apply()
    }

    private val _receivedClear = MutableStateFlow(ReceivedClear.fromKey(prefs.getString("receivedClear", null)))
    /** How long received wishlist cards are kept. Since 1.20. */
    val receivedClear: StateFlow<ReceivedClear> = _receivedClear

    fun setReceivedClear(v: ReceivedClear) {
        _receivedClear.value = v
        prefs.edit().putString("receivedClear", v.name).stamp().apply()
    }

    private val _receivedToCollection = MutableStateFlow(prefs.getBoolean("receivedToCollection", false))
    /** Wishlist cards marked as received go into the collection (Unsorted) right away. Since 1.20. */
    val receivedToCollection: StateFlow<Boolean> = _receivedToCollection

    fun setReceivedToCollection(on: Boolean) {
        _receivedToCollection.value = on
        prefs.edit().putBoolean("receivedToCollection", on).stamp().apply()
    }

    private val _swipeRight = MutableStateFlow(SwipeAction.fromKey(prefs.getString("swipeRight", null), SwipeAction.DEFAULT_RIGHT))
    /** What swiping a collection card to the right does. Since 1.20. */
    val swipeRight: StateFlow<SwipeAction> = _swipeRight

    private val _swipeLeft = MutableStateFlow(SwipeAction.fromKey(prefs.getString("swipeLeft", null), SwipeAction.DEFAULT_LEFT))
    /** What swiping a collection card to the left does. Since 1.20. */
    val swipeLeft: StateFlow<SwipeAction> = _swipeLeft

    fun setSwipe(right: Boolean, v: SwipeAction) {
        if (right) _swipeRight.value = v else _swipeLeft.value = v
        prefs.edit().putString(if (right) "swipeRight" else "swipeLeft", v.key).stamp().apply()
    }

    private val _binderOrder = MutableStateFlow(BinderOrder.decode(prefs.getString("binderOrder", null)))
    /** The binder bar's order, as arranged ([BinderOrder] keys). Since 1.20. */
    val binderOrder: StateFlow<List<String>> = _binderOrder

    fun setBinderOrder(keys: List<String>) {
        _binderOrder.value = keys
        prefs.edit().putString("binderOrder", BinderOrder.encode(keys)).stamp().apply()
    }

    private val _alertPriceType = MutableStateFlow(PriceType.fromKey(prefs.getString("alertPriceType", PriceType.LOW.key)))
    /** Which Cardmarket price wishlist alerts compare (lowest listing by default). Since 1.20. */
    val alertPriceType: StateFlow<PriceType> = _alertPriceType

    fun setAlertPriceType(t: PriceType) {
        _alertPriceType.value = t
        prefs.edit().putString("alertPriceType", t.key).stamp().apply()
    }

    /** Preferences that follow the owner to their other phone through sync (with when they last changed). Since 1.20. */
    fun syncedPrefs() = SyncPrefs(
        updatedAt = prefs.getLong(PREFS_UPDATED_AT, 0L),
        values = buildMap {
            put("priceType", priceType.value.key)
            put("currency", currency.value.name)
            put("priceSource", priceSource.value.key)
            put("tolerancePct", tolerancePct.value.toString())
            put("collectionView", collectionView.value.name)
            put("collectionSort", collectionSort.value.encode())
            put("tradeColumns", tradeColumns.value.toString())
            put("wishSections", wishSections.value.toString())
            put("receivedClear", receivedClear.value.name)
            put("receivedToCollection", receivedToCollection.value.toString())
            put("swipeRight", swipeRight.value.key)
            put("swipeLeft", swipeLeft.value.key)
            put("binderOrder", BinderOrder.encode(binderOrder.value))
            put("alertPriceType", alertPriceType.value.key)
        },
    )

    /** Takes over preferences that came in through sync (without stamping them as changed here). */
    fun applySyncedPrefs(p: SyncPrefs) {
        val v = p.values
        val e = prefs.edit()
        v["priceType"]?.let { _priceType.value = PriceType.fromKey(it); e.putString("priceType", it) }
        v["priceSource"]?.let { _priceSource.value = PriceSource.fromKey(it); e.putString("priceSource", it) }
        v["currency"]?.let { _currency.value = AppCurrency.fromKey(it); e.putString("currency", it) }
        v["tolerancePct"]?.toIntOrNull()?.let { _tolerance.value = it; e.putInt("tolerancePct", it) }
        v["collectionView"]?.let { _collectionView.value = CollectionView.fromKey(it); e.putString("collectionView", it) }
        v["collectionSort"]?.let { _collectionSort.value = SortSpec.decode(it); e.putString("collectionSort", it) }
        v["tradeColumns"]?.toBooleanStrictOrNull()?.let { _tradeColumns.value = it; e.putBoolean("tradeColumns", it) }
        v["wishSections"]?.toBooleanStrictOrNull()?.let { _wishSections.value = it; e.putBoolean("wishSections", it) }
        v["receivedClear"]?.let { _receivedClear.value = ReceivedClear.fromKey(it); e.putString("receivedClear", it) }
        v["receivedToCollection"]?.toBooleanStrictOrNull()?.let { _receivedToCollection.value = it; e.putBoolean("receivedToCollection", it) }
        v["swipeRight"]?.let { _swipeRight.value = SwipeAction.fromKey(it, SwipeAction.DEFAULT_RIGHT); e.putString("swipeRight", it) }
        v["swipeLeft"]?.let { _swipeLeft.value = SwipeAction.fromKey(it, SwipeAction.DEFAULT_LEFT); e.putString("swipeLeft", it) }
        v["binderOrder"]?.let { _binderOrder.value = BinderOrder.decode(it); e.putString("binderOrder", it) }
        v["alertPriceType"]?.let { _alertPriceType.value = PriceType.fromKey(it); e.putString("alertPriceType", it) }
        e.putLong(PREFS_UPDATED_AT, p.updatedAt).apply()
    }

    private fun android.content.SharedPreferences.Editor.stamp() = putLong(PREFS_UPDATED_AT, System.currentTimeMillis())

    fun setPriceGuideFetched(createdAt: String?, at: Long) {
        _lastFetch.value = at
        _guideDate.value = createdAt
        prefs.edit().putLong("lastPriceFetch", at).putString("priceGuideDate", createdAt).apply()
    }

    companion object {
        private const val PREFS_UPDATED_AT = "syncedPrefsUpdatedAt"
    }
}
