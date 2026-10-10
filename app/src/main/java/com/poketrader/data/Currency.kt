package com.poketrader.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** The currencies prices can be shown in. Prices are kept in euros (Cardmarket's) and converted for display. Since 1.15. */
enum class AppCurrency(val label: String) {
    EUR("Euro (€)"),
    DKK("Danish krone (kr.)"),
    USD("US dollar ($)");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.name == key } ?: EUR
    }
}

/** The European Central Bank's reference rates of one day: how much of each currency one euro buys. */
data class ExchangeRates(val date: String, val perEuro: Map<String, Double>) {
    fun rate(c: AppCurrency): Double? = if (c == AppCurrency.EUR) 1.0 else perEuro[c.name]

    fun encode() = (listOf(date) + perEuro.map { (k, v) -> "$k=$v" }).joinToString("\n")

    companion object {
        /** The krone is pegged to the euro at 7.46038 (within a narrow band): good enough until the first download. */
        val FALLBACK = ExchangeRates("", mapOf("DKK" to 7.46038))

        fun decode(text: String): ExchangeRates? {
            val lines = text.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return null
            val rates = lines.drop(1).mapNotNull { l -> l.split('=').takeIf { it.size == 2 }?.let { (k, v) -> v.toDoubleOrNull()?.let { k to it } } }.toMap()
            return ExchangeRates(lines[0], rates).takeIf { rates.isNotEmpty() }
        }

        /** Reads the ECB's eurofxref-daily.xml. */
        fun parseEcb(xml: String): ExchangeRates? {
            val date = Regex("""time=['"](\d{4}-\d{2}-\d{2})['"]""").find(xml)?.groupValues?.get(1) ?: return null
            val rates = Regex("""currency=['"]([A-Z]{3})['"]\s+rate=['"]([0-9.]+)['"]""").findAll(xml)
                .mapNotNull { m -> m.groupValues[2].toDoubleOrNull()?.takeIf { it > 0 }?.let { m.groupValues[1] to it } }.toMap()
            return if (rates.isEmpty()) null else ExchangeRates(date, rates)
        }
    }
}

/** What prices are shown in: [currency], and how many of it one euro buys. */
data class DisplayCurrency(val currency: AppCurrency = AppCurrency.EUR, val perEuro: Double = 1.0) {
    companion object {
        /** The chosen currency at [rates]; euros while its rate isn't known yet. */
        fun of(currency: AppCurrency, rates: ExchangeRates?): DisplayCurrency {
            val r = rates?.rate(currency) ?: ExchangeRates.FALLBACK.rate(currency) ?: return DisplayCurrency()
            return DisplayCurrency(currency, r)
        }
    }
}

/**
 * Amounts in euros, shown in the chosen currency. [display] is Compose state, so screens redraw when
 * the currency or rate changes. Since 1.15.
 */
object Money {
    var display by mutableStateOf(DisplayCurrency())

    fun toDisplay(eur: Double): Double = eur * display.perEuro

    /** An amount typed in the display currency, in euros. */
    fun toEur(amount: Double): Double = amount / display.perEuro

    private fun eurFormat(decimals: Int) = NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
        currency = java.util.Currency.getInstance("EUR")
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
    }
    private val eur2 by lazy { eurFormat(2) }
    private val eur0 by lazy { eurFormat(0) }
    private fun number(v: Double, decimals: Int, locale: Locale) =
        DecimalFormat(if (decimals == 0) "#,##0" else "#,##0." + "0".repeat(decimals), DecimalFormatSymbols.getInstance(locale)).format(v)

    /** With the currency's sign the way it's usually written: "€3.50" (as the phone writes euros), "$3.50", "3,50 kr.". */
    /** [decimals] is 2 or 0. */
    fun format(eur: Double, decimals: Int = 2): String {
        val v = toDisplay(eur)
        val sign = if (v < 0) "-" else ""
        return when (display.currency) {
            AppCurrency.EUR -> (if (decimals == 0) eur0 else eur2).format(v)
            AppCurrency.USD -> "$sign$" + number(abs(v), decimals, Locale.US)
            AppCurrency.DKK -> sign + number(abs(v), decimals, DANISH) + " kr."
        }
    }

    /** "€10k", "$1.2M", "12k kr.": for tight spaces; small amounts keep their cents. */
    fun short(eur: Double): String {
        val v = toDisplay(eur)
        if (abs(v) < 1000) return format(eur)
        val (n, unit) = if (abs(v) >= 1_000_000) v / 1_000_000 to "M" else v / 1000 to "k"
        return when (display.currency) {
            AppCurrency.EUR -> {
                val number = DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.getDefault())).format(n) + unit
                if (!eur2.format(1.0).first().isDigit()) "€$number" else "$number €"
            }
            AppCurrency.USD -> "$" + DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.US)).format(n) + unit
            AppCurrency.DKK -> DecimalFormat("0.#", DecimalFormatSymbols.getInstance(DANISH)).format(n) + unit + " kr."
        }
    }

    /**
     * For narrow chart columns: euros as "€333" (as before), other currencies as a bare number
     * ("2,5k", "119"), with the currency named nearby.
     */
    fun column(eur: Double): String {
        if (display.currency == AppCurrency.EUR) return format(eur, decimals = 0)
        val v = toDisplay(eur)
        val locale = if (display.currency == AppCurrency.DKK) DANISH else Locale.US
        return if (abs(v) >= 1000) DecimalFormat("0.#", DecimalFormatSymbols.getInstance(locale)).format(v / 1000) + "k"
        else DecimalFormat("0", DecimalFormatSymbols.getInstance(locale)).format(v)
    }

    /** For a text field in the display currency: "3.50" (or "" for none). */
    fun input(eur: Double?): String = eur?.let { "%.2f".format(toDisplay(it)) } ?: ""

    /** The symbol for labels and placeholders: "€", "$", "kr.". */
    val symbol get() = when (display.currency) {
        AppCurrency.EUR -> "€"
        AppCurrency.USD -> "$"
        AppCurrency.DKK -> "kr."
    }

    private val DANISH = Locale("da", "DK")
}

/**
 * Downloads and keeps the ECB's daily reference rates (free, no account; updated each working day
 * around 16:00 CET). The last ones are kept on the phone, so prices convert offline too. Since 1.15.
 */
class ExchangeRateStore(context: Context, private val http: OkHttpClient) {
    private val file = File(context.filesDir, "exchange_rates.txt")
    private val lock = Mutex()
    private val _rates = MutableStateFlow<ExchangeRates?>(null)
    val rates: StateFlow<ExchangeRates?> = _rates

    /** Reads the saved rates, then fetches new ones if they're missing or over 12 hours old. */
    suspend fun load() {
        val saved = withContext(Dispatchers.IO) { if (file.exists()) ExchangeRates.decode(file.readText()) else null }
        if (saved != null) _rates.value = saved
        if (saved == null || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) refresh()
    }

    suspend fun refresh(): Boolean = lock.withLock {
        try {
            val xml = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(ECB_URL).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            } ?: return@withLock false
            val parsed = ExchangeRates.parseEcb(xml) ?: return@withLock false
            _rates.value = parsed
            withContext(Dispatchers.IO) { file.writeText(parsed.encode()) }
            true
        } catch (e: Exception) {
            // Offline: keep the saved rates.
            false
        }
    }

    companion object {
        const val ECB_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"
        private const val MAX_AGE_MS = 12L * 60 * 60 * 1000
    }
}
