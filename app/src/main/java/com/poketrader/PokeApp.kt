package com.poketrader

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.util.DebugLogger
import com.poketrader.data.AppDatabase
import com.poketrader.data.CardmarketCatalog
import com.poketrader.data.CollectionJump
import com.poketrader.data.DataUpdater
import com.poketrader.data.FallbackImageSets
import com.poketrader.data.LimitlessCards
import com.poketrader.data.NetworkMonitor
import com.poketrader.data.PriceGuideRepository
import com.poketrader.data.Repository
import com.poketrader.data.SetCatalog
import com.poketrader.data.Settings
import com.poketrader.data.TcgdexApi
import com.poketrader.data.TradeWithItems
import com.poketrader.data.TcgplayerImages
import com.poketrader.data.ValueHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class PokeApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { container.http }
        .crossfade(true)
        .apply { if (BuildConfig.DEBUG) logger(DebugLogger()) }
        .build()
}

/** Hand-rolled dependency container; one instance for the whole app. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "PokeTraderAndroid/1.0").build())
        }
        .build()
    val db = AppDatabase.build(context)
    val settings = Settings(context)
    val exchangeRates = com.poketrader.data.ExchangeRateStore(context, http)
    val tcgdex = TcgdexApi(http)
    val priceSources = com.poketrader.data.PriceSourceStore(context, db, tcgdex)
    val sets = SetCatalog(context, tcgdex)
    val fallbackImages = FallbackImageSets(context, http, sets)
    val tcgplayerImages = TcgplayerImages(context, tcgdex)
    val network = NetworkMonitor(context)
    val prices = PriceGuideRepository(context, http, db, settings)
    val repo = Repository(db, tcgdex, prices, appScope)
    val catalog = CardmarketCatalog(context, http, db, settings, tcgdex).also { cat ->
        tcgdex.repair = { card, lang -> cat.repair(card, lang) }
    }
    val limitless = LimitlessCards(context, http, tcgdex, sets).also { l ->
        tcgdex.missingJapanese = { setId, number -> l.card(setId, number) }
    }
    val history = ValueHistory(db)
    /** Set by the stats screen; the collection shows these cards when it next appears. Since 1.13. */
    val collectionJump = MutableStateFlow<CollectionJump?>(null)
    val updater = DataUpdater(context, settings, prices, catalog, repo, network, history, priceSources)

    init {
        // Prices show in the chosen currency at the latest rate (since 1.15), from the chosen source (since 1.16).
        // Set right away, so the first screen is right; afterwards the watchers below only pass on changes.
        val display = { cur: com.poketrader.data.AppCurrency, rates: com.poketrader.data.ExchangeRates? -> com.poketrader.data.DisplayCurrency.of(cur, rates) }
        val display0 = display(settings.currency.value, exchangeRates.rates.value)
        com.poketrader.data.Money.display = display0
        appScope.launch {
            kotlinx.coroutines.flow.combine(settings.currency, exchangeRates.rates, display)
                .dropWhile { it == display0 }
                .collect { com.poketrader.data.Money.display = it }
        }
        val source0 = settings.priceSource.value
        com.poketrader.data.Pricing.source = source0
        appScope.launch { settings.priceSource.dropWhile { it == source0 }.collect { com.poketrader.data.Pricing.source = it } }
        val usd = { r: com.poketrader.data.ExchangeRates? -> r?.perEuro?.get("USD") }
        val usd0 = usd(exchangeRates.rates.value)
        com.poketrader.data.Pricing.usdPerEuro = usd0
        appScope.launch { exchangeRates.rates.map(usd).dropWhile { it == usd0 }.collect { com.poketrader.data.Pricing.usdPerEuro = it } }
        tcgdex.onCard = { card, lang -> priceSources.fromTcgdex(card, lang) }
    }

    /** A trade deleted on its own screen, so the trade list can offer Undo once it's back on screen. */
    @Volatile
    var deletedTrade: TradeWithItems? = null
}

val Context.container: AppContainer get() = (applicationContext as PokeApp).container
