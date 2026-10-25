package com.poketrader.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The rules of price alerts, apart from the phone, so they can be tested. Since 1.20 (as MTG Trader's). */
object PriceAlerts {
    /** What a stored price is: "cardmarket:low" (with the price type) or the source's key. */
    fun basis(source: PriceSource, type: PriceType) = if (source == PriceSource.CARDMARKET) "cardmarket:${type.key}" else source.key

    /** How a stored price is named on a card: "Low", "Trend", "TCGplayer"… */
    fun basisLabel(basis: String): String = when {
        basis.startsWith("cardmarket:") -> PriceType.fromKey(basis.substringAfter(':')).short
        else -> PriceSource.fromKey(basis).label
    }

    /** "Cardmarket's lowest listing", "TCGplayer's market price": for a notification and the card's window. */
    fun basisLong(basis: String): String = when {
        basis.startsWith("cardmarket:") -> "Cardmarket's " + PriceType.fromKey(basis.substringAfter(':')).label.lowercase()
        basis == PriceSource.TCGPLAYER.key -> "TCGplayer's market price"
        else -> "${PriceSource.fromKey(basis).label}'s price"
    }

    /**
     * A version's price at [source], in euros: Cardmarket's [type] from the price guide ([cm], by product),
     * TCGplayer's from what the phone has. Null when it has none.
     */
    fun priceOf(v: CardRef, source: PriceSource, type: PriceType, cm: Map<Int, PriceEntity>): Double? {
        val cardmarket = v.cardmarketId?.let { cm[it] }?.toSet(v.holoPrice)?.best(type)
        return Pricing.at(source, v, cardmarket)?.takeIf { it > 0 }
    }

    /** Each version with its price (cheapest first, those without a price last). */
    fun ranked(versions: List<CardRef>, priceOf: (CardRef) -> Double?): List<Pair<CardRef, Double?>> =
        versions.map { it to priceOf(it) }.sortedWith(compareBy(nullsLast()) { it.second })

    /** Whether to notify, and the level notified for afterwards. */
    data class Step(val notify: Boolean, val alertedLevel: Double?)

    /**
     * One check of a card with alert [level] (in euros at today's rate) whose cheapest price is now [best]:
     * notify once when it's at or below the level, then stay quiet until it's been above the level again
     * (or the level changes). Without a price nothing changes; without a level there's nothing to notify.
     * [key] is the level as typed, which stays the same while its value in euros moves a little each day.
     */
    fun step(level: Double?, best: Double?, alertedLevel: Double?, key: Double? = level): Step = when {
        level == null -> Step(false, null)
        best == null -> Step(false, alertedLevel)
        best <= level + 0.000_1 -> Step(alertedLevel != key, key)
        else -> Step(false, null)
    }

    private val versionList = ListSerializer(CardRef.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    fun encodeVersions(v: List<CardRef>): String = json.encodeToString(versionList, v)
    fun decodeVersions(s: String?): List<CardRef>? = s?.let { runCatching { json.decodeFromString(versionList, it) }.getOrNull() }
}

/**
 * Looks up the prices of wishlist cards for their alerts and sends the notifications. Runs after each
 * price update (when the app opens, and in the background every few hours), so alerts are as fresh as
 * the price data. For "any version" cards it takes the cheapest version (the versions come from TCGdex,
 * looked up daily). Since 1.20.
 */
class WishlistWatch(
    context: Context,
    private val db: AppDatabase,
    private val settings: Settings,
    private val tcgdex: TcgdexApi,
    private val priceSources: PriceSourceStore,
) {
    private val lock = Mutex()
    private val dao = db.wishAlertDao()

    val notifier = AlertNotifier(context)

    private var soon: Job? = null

    /** A check shortly (a setting just changed; quick changes in a row make one check). */
    fun checkSoon(scope: CoroutineScope) {
        soon?.cancel()
        soon = scope.launch {
            delay(1_500)
            check()
        }
    }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /**
     * Checks the wishlist ([only]: just these cards). With [notify] off (a level just set, or the card just
     * changed) a card already below its level is noted without a notification: you're looking at it.
     * [online] off skips looking up versions and works with what the phone has.
     */
    suspend fun check(notify: Boolean = true, only: Set<Long>? = null, online: Boolean = true) = lock.withLock {
        _running.value = true
        try {
            run(notify, only, online)
        } finally {
            _running.value = false
        }
    }

    private suspend fun run(notify: Boolean, only: Set<Long>?, online: Boolean) {
        val items = db.wishlistDao().all()
        if (only == null) {
            if (items.isEmpty()) dao.clear() else dao.keepOnly(items.map { it.id })
        }
        val source = settings.priceSource.value
        val type = settings.alertPriceType.value
        val basis = PriceAlerts.basis(source, type)
        val todo = items.filter { (only == null || it.id in only) && !it.isPending && it.orderedAt == null && it.receivedAt == null }
            .sortedBy { it.alertBelow == null }
        for (item in todo) {
            var row = dao.get(item.id) ?: WishAlert(item.id)
            val now = System.currentTimeMillis()
            val versions: List<CardRef> = if (!item.anyVariant) listOf(item.card) else {
                val cached = PriceAlerts.decodeVersions(row.variants)?.takeIf { v -> v.firstOrNull()?.cardId == item.card.cardId }
                if (cached != null && (!online || now - row.variantsAt < VERSIONS_MAX_AGE_MS)) cached
                else {
                    val fetched = if (online) runCatching { tcgdex.card(item.card.cardId, item.card.dataLang)?.printings(item.card.dataLang) }.getOrNull() else null
                    if (!fetched.isNullOrEmpty()) {
                        row = row.copy(variants = PriceAlerts.encodeVersions(fetched), variantsAt = now)
                        fetched
                    } else cached ?: listOf(item.card)
                }
            }
            val cm = versions.mapNotNull { it.cardmarketId }.distinct().chunked(900)
                .flatMap { db.priceDao().getMany(it) }.associateBy { it.idProduct }
            if (source != PriceSource.CARDMARKET) runCatching { priceSources.load(versions.map(Pricing::key)) }
            val cheapest = PriceAlerts.ranked(versions) { PriceAlerts.priceOf(it, source, type, cm) }.firstOrNull()?.takeIf { it.second != null }
            row = row.copy(basis = basis, price = cheapest?.second, variantLabel = cheapest?.first?.variantLabel)

            val step = PriceAlerts.step(item.levelEur, row.price, row.alertedLevel, key = item.alertBelow)
            if (step.notify && notify && row.price != null) notifier.show(item, row.price!!, row, basis)
            row = row.copy(alertedLevel = step.alertedLevel, checkedAt = now)
            dao.put(row)
        }
    }

    companion object {
        private const val VERSIONS_MAX_AGE_MS = 20L * 3600 * 1000
    }
}

/** The "Price alerts" notifications: one per card, tapping it opens the card on the wishlist. Since 1.20. */
class AlertNotifier(private val context: Context) {
    /** Whether notifications can show (Android 13 and later ask first). */
    fun allowed(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun channel() {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(CHANNEL, "Wishlist price alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When a wishlist card drops to the price you set on it"
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }

    fun show(item: WishlistItem, price: Double, row: WishAlert, basis: String) {
        if (!allowed()) return
        channel()
        val title = "${item.card.name}: ${Money.format(price)}"
        val where = PriceAlerts.basisLong(basis) + (row.variantLabel?.takeIf { item.anyVariant && it.isNotBlank() }?.let { " ($it)" } ?: "")
        val level = item.alertBelow?.let { Money.kept(it, item.alertCurrency) } ?: Money.format(price)
        val text = "${item.card.setName} #${item.card.numberLabel} · $where · your level: $level"
        val open = Intent(context, com.poketrader.MainActivity::class.java)
            .setAction(ACTION_OPEN_WISH).putExtra(EXTRA_WISH_ID, item.id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, item.id.toInt(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.poketrader.R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE + item.id.toInt(), n) }
    }

    companion object {
        const val CHANNEL = "price_alerts"
        const val ACTION_OPEN_WISH = "com.poketrader.OPEN_WISH"
        const val EXTRA_WISH_ID = "wishId"
        private const val NOTIFICATION_BASE = 10_000
    }
}
