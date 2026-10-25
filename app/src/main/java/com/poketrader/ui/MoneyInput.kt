package com.poketrader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.poketrader.data.AppCurrency
import com.poketrader.data.Money

// Typing a price in the currency it was paid in, whatever currency the app shows; the price is kept
// in that currency. Since 1.20.

/**
 * The currency last chosen in a price field, so the next one starts on it (kept per phone, see
 * [com.poketrader.data.Settings.typedCurrency]). Until one is chosen, fields start on the display currency.
 */
object TypedCurrency {
    var last by mutableStateOf<AppCurrency?>(null)

    /** Saves a choice; set up by the app. */
    var save: (AppCurrency) -> Unit = {}

    fun pick(c: AppCurrency) {
        last = c
        save(c)
    }

    /** The currency an empty price field starts on. */
    val start: AppCurrency get() = last ?: Money.display.currency
}

/** "€", "$", "kr.". */
fun currencySymbol(c: AppCurrency) = when (c) {
    AppCurrency.EUR -> "€"
    AppCurrency.USD -> "$"
    AppCurrency.DKK -> "kr."
}

/** The currency a kept price's code names ("DKK"; null is euros). */
fun currencyOf(code: String?): AppCurrency = AppCurrency.entries.firstOrNull { it.name == code } ?: AppCurrency.EUR

/** How a currency is kept with a price: null for euros (as prices from before 1.20 are), else its code. */
fun currencyCode(c: AppCurrency): String? = c.name.takeIf { c != AppCurrency.EUR }

/** A price being typed: the text and the currency it's in. */
@Stable
class MoneyInput(text: String, currency: AppCurrency) {
    private var typedText by mutableStateOf(text)

    /** The amount before rounding to cents in [currency], until the text is edited (see [switchTo]). */
    private var exact: Double? = null

    var text: String
        get() = typedText
        set(value) {
            if (value != typedText) exact = null
            typedText = value
        }

    var currency by mutableStateOf(currency)
        private set

    /** The amount in [currency], as saved: what was typed, or the exact amount a currency switch came to. */
    val amount: Double? get() = exact ?: Fmt.parseMoney(text)

    /** The currency to save with [amount]: null for euros. */
    val code: String? get() = currencyCode(currency)

    /** In euros at today's rate; null when empty or the rate isn't known. */
    val eur: Double? get() = amount?.let { Money.eurOf(it, currency.name) }

    /**
     * Shows the price in [c] instead, converted at today's rate, so it keeps its value: 300 kr. becomes
     * 40,21 €, and back again exactly 300,00 kr. An empty field, or one whose rate isn't known, only
     * changes its currency.
     */
    fun switchTo(c: AppCurrency) {
        if (c == currency) return
        val inEur = eur
        val converted = inEur?.let { Money.amountIn(it, c) }
        if (converted != null) {
            // Back to where it started: the amount as it was saved, not one rounded twice.
            val back = original?.takeIf { it.second == c }?.first
            typedText = "%.2f".format(back ?: converted)
            exact = back ?: converted
        }
        currency = c
    }

    /** The price as it was saved, so switching back to its currency gives exactly that amount again. */
    private var original: Pair<Double, AppCurrency>? = null

    companion object {
        /**
         * For a price kept as [saved] in [currency] ("DKK", null = euros): a saved one shows in its own
         * currency as it was typed; an empty field starts on the currency last chosen.
         */
        fun of(saved: Double?, currency: String?): MoneyInput =
            if (saved != null) {
                val c = currencyOf(currency)
                MoneyInput("%.2f".format(saved), c).also { it.exact = saved; it.original = saved to c }
            } else {
                MoneyInput("", TypedCurrency.start)
            }
    }
}

@Composable
fun rememberMoneyInput(saved: Double?, currency: String?, key: Any? = Unit): MoneyInput = remember(key) { MoneyInput.of(saved, currency) }

/** The small "€ ▾" button at the end of a price field, choosing the currency typed in. */
@Composable
fun CurrencyButton(currency: AppCurrency, enabled: Boolean = true, onPick: (AppCurrency) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("${currencySymbol(currency)} ▾", fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppCurrency.entries.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.label, fontWeight = if (c == currency) FontWeight.Bold else null) },
                    onClick = { open = false; onPick(c) },
                )
            }
        }
    }
}

/** What a price typed in another currency comes to in the one shown: "≈ 74,61 kr."; null when it's the same. */
fun convertedHint(input: MoneyInput): String? =
    input.eur?.takeIf { input.currency != Money.display.currency }?.let { "≈ ${Money.format(it)}" }

/**
 * A price field with its currency button: type the amount as paid, in €, kr. or $ (choosing another
 * converts what's there); [onChange] runs after each change of the text or the currency. Shown in
 * another currency than the app's, the field says what it comes to.
 */
@Composable
fun MoneyField(
    input: MoneyInput,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    onChange: () -> Unit = {},
) {
    val hint = convertedHint(input)
    OutlinedTextField(
        value = input.text,
        onValueChange = { input.text = it; onChange() },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        trailingIcon = {
            CurrencyButton(input.currency, enabled) { c ->
                input.switchTo(c)
                TypedCurrency.pick(c)
                onChange()
            }
        },
        supportingText = hint?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/**
 * Asks which currency a stack's purchase price is kept in when cards join it and the two prices are in
 * different currencies (the owner's choice, 2026-10-25). Since 1.20.
 */
@Stable
class JoinCurrencyAsk {
    var pending by mutableStateOf<Pair<List<AppCurrency>, (AppCurrency) -> Unit>?>(null)

    /** Runs [then] right away when no choice is needed ([currencies] empty), else once a currency is picked. */
    fun ask(currencies: List<AppCurrency>, then: (AppCurrency?) -> Unit) {
        if (currencies.size < 2) then(null) else pending = currencies to { c -> then(c) }
    }
}

@Composable
fun rememberJoinCurrencyAsk(): JoinCurrencyAsk = remember { JoinCurrencyAsk() }

/** The question of a [JoinCurrencyAsk], while one is pending. */
@Composable
fun JoinCurrencyDialog(ask: JoinCurrencyAsk) {
    val (currencies, go) = ask.pending ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { ask.pending = null },
        title = { Text("Which currency?") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    "These cards join a stack whose purchase price is in another currency. The stack gets the average " +
                        "paid, converted at today's rate, kept in:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                currencies.forEach { c ->
                    TextButton(onClick = { ask.pending = null; go(c) }) { Text(c.label) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { ask.pending = null }) { Text("Cancel") } },
    )
}
