package com.poketrader

import com.poketrader.scan.FoilMeter
import com.poketrader.scan.Shine
import com.poketrader.scan.ShineSample
import org.junit.Assert.assertEquals
import org.junit.Test

class FoilMeterTest {
    private fun frames(n: Int, f: (Int) -> ShineSample) = (0 until n).map(f)

    @Test
    fun plainCardIsNormal() {
        // Colourful artwork, smooth text box, steady brightness.
        assertEquals(Shine.NORMAL, FoilMeter.judge(frames(5) { ShineSample(9f, 2f, 120f, 200f, 160f) }))
    }

    @Test
    fun glitteringTextBoxIsReverseHolo() {
        assertEquals(Shine.REVERSE, FoilMeter.judge(frames(5) { ShineSample(8f, 11f, 120f, 190f, 160f) }))
    }

    @Test
    fun flickeringArtworkIsHolo() {
        val s = frames(6) { i -> ShineSample(12f, 2f, if (i % 2 == 0) 110f else 150f, 200f, 160f) }
        assertEquals(Shine.HOLO, FoilMeter.judge(s))
    }

    @Test
    fun tooFewFramesSayNothing() {
        assertEquals(Shine.NORMAL, FoilMeter.judge(frames(2) { ShineSample(8f, 11f, 120f, 190f, 160f) }))
    }
}
