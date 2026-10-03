package com.poketrader.scan

import android.media.Image
import kotlin.math.abs
import kotlin.math.sqrt

/** What a card's surface looks like to the camera. */
enum class Shine { NORMAL, HOLO, REVERSE }

/**
 * One frame's readings of the card's artwork and its text box (attacks), from the camera image:
 * how much the colour changes from one pixel to the next (foil glitters in rainbow colours, plain
 * print is smooth) and the average brightness (foil brightens and darkens as the card tilts).
 */
data class ShineSample(val artTexture: Float, val textTexture: Float, val artLuma: Float, val textLuma: Float, val cardLuma: Float)

/**
 * Tells normal cards, holos (foil artwork) and reverse holos (foil everywhere but the artwork)
 * apart, as well as a phone camera allows: reverse holos give the text box more colour texture
 * than the artwork, and holo artwork flickers in brightness from frame to frame while the text box
 * stays steady. Unsure readings say NORMAL, so the scanner falls back to its usual guess. Since 1.9.
 */
object FoilMeter {
    // Card layout (current and most older frames), as fractions of the card.
    private val ART = floatArrayOf(0.10f, 0.12f, 0.90f, 0.47f)
    private val TEXT = floatArrayOf(0.10f, 0.56f, 0.90f, 0.85f)
    private val CARD = floatArrayOf(0.06f, 0.05f, 0.94f, 0.95f)

    /** Readings from a YUV camera frame; [guide] is in upright coordinates of size [w]×[h]. */
    fun sample(image: Image, rotation: Int, guide: Box, w: Int, h: Int): ShineSample? = runCatching {
        val planes = image.planes
        if (planes.size < 3) return null
        val y = planes[0]
        val u = planes[1]
        val v = planes[2]
        val sw = image.width
        val sh = image.height
        fun at(ux: Int, uy: Int): IntArray {
            // Upright point → sensor point.
            val (sx, sy) = when (rotation) {
                90 -> uy to sh - 1 - ux
                180 -> sw - 1 - ux to sh - 1 - uy
                270 -> sw - 1 - uy to ux
                else -> ux to uy
            }
            val cx = sx.coerceIn(0, sw - 1)
            val cy = sy.coerceIn(0, sh - 1)
            val yy = y.buffer.get(cy * y.rowStride + cx * y.pixelStride).toInt() and 255
            val ci = (cy / 2) * u.rowStride + (cx / 2) * u.pixelStride
            val cv = (cy / 2) * v.rowStride + (cx / 2) * v.pixelStride
            return intArrayOf(yy, u.buffer.get(ci).toInt() and 255, v.buffer.get(cv).toInt() and 255)
        }
        fun region(r: FloatArray): Pair<Float, Float> {
            val x0 = (guide.left + guide.width * r[0]).toInt().coerceIn(0, w - 4)
            val x1 = (guide.left + guide.width * r[2]).toInt().coerceIn(x0 + 4, w - 4)
            val y0 = (guide.top + guide.height * r[1]).toInt().coerceIn(0, h - 1)
            val y1 = (guide.top + guide.height * r[3]).toInt().coerceIn(y0 + 1, h - 1)
            val step = maxOf(3, (x1 - x0) / 120)
            var texture = 0.0
            var luma = 0.0
            var n = 0
            var py = y0
            while (py < y1) {
                var px = x0
                while (px < x1) {
                    val a = at(px, py)
                    val b = at(px + 2, py)
                    // Colour change only: black text on a pale box is a brightness edge, not a colour one.
                    texture += abs(a[1] - b[1]) + abs(a[2] - b[2])
                    luma += a[0]
                    n++
                    px += step
                }
                py += step
            }
            return if (n == 0) 0f to 0f else (texture / n).toFloat() to (luma / n).toFloat()
        }
        val (at, al) = region(ART)
        val (tt, tl) = region(TEXT)
        ShineSample(at, tt, al, tl, region(CARD).second)
    }.getOrNull()

    /** The verdict over several frames of one card, or NORMAL when there's too little to go on. */
    fun judge(samples: List<ShineSample>): Shine {
        if (samples.size < 3) return Shine.NORMAL
        val art = samples.map { it.artTexture }.sorted()[samples.size / 2]
        val text = samples.map { it.textTexture }.sorted()[samples.size / 2]
        // Plain print: the artwork is far more colourful than the text box. Foil text box: the other way round.
        if (text > 2.5f && text > art * 1.15f) return Shine.REVERSE
        // Brightness relative to the frame's own (so the camera's exposure changes cancel out).
        val artRel = samples.map { it.artLuma / it.cardLuma.coerceAtLeast(1f) }
        val textRel = samples.map { it.textLuma / it.cardLuma.coerceAtLeast(1f) }
        val artFlicker = std(artRel.zipWithNext { a, b -> b - a })
        val textFlicker = std(textRel.zipWithNext { a, b -> b - a })
        if (artFlicker > 0.012f && artFlicker > textFlicker * 2.5f && art > text * 1.3f) return Shine.HOLO
        return Shine.NORMAL
    }

    private fun std(xs: List<Float>): Float {
        if (xs.isEmpty()) return 0f
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size).toFloat()
    }
}
