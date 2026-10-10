package com.poketrader

import com.poketrader.data.AppCurrency
import com.poketrader.data.DisplayCurrency
import com.poketrader.data.ExchangeRates
import com.poketrader.data.Money
import com.poketrader.ui.Fmt
import com.poketrader.ui.rateLine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** 1.15: prices in other currencies. */
class CurrencyTest {
    // The start of the ECB's eurofxref-daily.xml, as published.
    private val ecb = """<?xml version="1.0" encoding="UTF-8"?>
<gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01" xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
	<gesmes:subject>Reference rates</gesmes:subject>
	<gesmes:Sender>
		<gesmes:name>European Central Bank</gesmes:name>
	</gesmes:Sender>
	<Cube>
		<Cube time='2026-10-09'>
			<Cube currency='USD' rate='1.0841'/>
			<Cube currency='JPY' rate='161.52'/>
			<Cube currency='DKK' rate='7.4612'/>
			<Cube currency='GBP' rate='0.8432'/>
		</Cube>
	</Cube>
</gesmes:Envelope>"""

    private val default = Locale.getDefault()

    @After
    fun reset() {
        Money.display = DisplayCurrency()
        Locale.setDefault(default)
    }

    @Test
    fun readsTheEcbRates() {
        val r = ExchangeRates.parseEcb(ecb)!!
        assertEquals("2026-10-09", r.date)
        assertEquals(1.0841, r.rate(AppCurrency.USD)!!, 1e-9)
        assertEquals(7.4612, r.rate(AppCurrency.DKK)!!, 1e-9)
        assertEquals(1.0, r.rate(AppCurrency.EUR)!!, 0.0)
        // Saved and read back.
        assertEquals(r, ExchangeRates.decode(r.encode()))
        assertNull(ExchangeRates.parseEcb("<html>Service unavailable</html>"))
        assertNull(ExchangeRates.decode(""))
    }

    @Test
    fun choosesTheRate() {
        val r = ExchangeRates.parseEcb(ecb)
        assertEquals(DisplayCurrency(AppCurrency.USD, 1.0841), DisplayCurrency.of(AppCurrency.USD, r))
        // Before the first download: kroner at the fixed rate, dollars wait (prices stay in euros).
        assertEquals(7.46038, DisplayCurrency.of(AppCurrency.DKK, null).perEuro, 1e-9)
        assertEquals(DisplayCurrency(), DisplayCurrency.of(AppCurrency.USD, null))
        assertEquals(DisplayCurrency(), DisplayCurrency.of(AppCurrency.EUR, r))
    }

    @Test
    fun formatsEachCurrencyItsOwnWay() {
        Locale.setDefault(Locale.US)
        assertEquals("€3.50", Fmt.money(3.5))
        assertEquals("€1,235", Fmt.wholeMoney(1234.6))

        Money.display = DisplayCurrency(AppCurrency.USD, 1.1)
        assertEquals("$3.85", Fmt.money(3.5))
        assertEquals("$1,358", Fmt.wholeMoney(1234.6))
        assertEquals("$1.4k", Fmt.shortMoney(1234.6))
        assertEquals("−$1.10", Fmt.signedMoney(-1.0))

        Money.display = DisplayCurrency(AppCurrency.DKK, 7.46)
        assertEquals("26,11 kr.", Fmt.money(3.5))
        assertEquals("9.211 kr.", Fmt.wholeMoney(1234.7))
        assertEquals("9,2k kr.", Fmt.shortMoney(1234.7))
        assertEquals("+7,46 kr.", Fmt.signedMoney(1.0))
        assertEquals("kr.", Money.symbol)
    }

    @Test
    fun typedAmountsAreKeptInEuros() {
        Money.display = DisplayCurrency(AppCurrency.DKK, 7.46)
        assertEquals(10.0, Fmt.parseMoneyEur("74,60")!!, 1e-9)
        assertNull(Fmt.parseMoneyEur(""))
        // A stored euro price shows in kroner in the field, and reads back the same.
        val shown = Money.input(10.0)
        assertTrue(shown, shown == "74.60" || shown == "74,60")
        assertEquals(10.0, Fmt.parseMoneyEur(shown)!!, 0.001)
    }

    @Test
    fun rateLineInSettings() {
        Locale.setDefault(Locale.US)
        val r = ExchangeRates.parseEcb(ecb)
        assertTrue(rateLine(AppCurrency.DKK, r).startsWith("1 € = 7.4612 DKK · rates of Oct 9, 2026"))
        assertTrue(rateLine(AppCurrency.USD, null).startsWith("Waiting"))
        assertTrue(rateLine(AppCurrency.DKK, null).contains("7.46"))
    }
}
