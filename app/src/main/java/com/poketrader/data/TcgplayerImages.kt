package com.poketrader.data

import android.content.Context
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Which card a picture is for, so a missing one can be looked up elsewhere. */
data class ImageKey(val cardId: String, val dataLang: String, val variantId: String? = null)

/**
 * Last-resort picture source: TCGplayer's product images. TCGdex lists a TCGplayer product id for
 * most international cards and some Japanese ones; finding it takes one card lookup, so the result
 * (including "none") is remembered on the phone.
 */
class TcgplayerImages(context: Context, private val api: TcgdexApi) {
    private val prefs = context.getSharedPreferences("tcgplayer_ids", Context.MODE_PRIVATE)
    private val memory = ConcurrentHashMap<String, Int>()

    /** The product id for [key]'s card, or null if it has none (or TCGdex can't be reached right now). */
    suspend fun productId(key: ImageKey): Int? {
        val k = "${key.dataLang}/${key.cardId}"
        val known = memory[k] ?: prefs.getInt(k, -1).takeIf { it >= 0 }
        if (known != null) return known.takeIf { it > 0 }
        val card = try {
            api.card(key.cardId, key.dataLang)
        } catch (e: IOException) {
            return null // offline: try again next time
        }
        val variants = card?.variantsDetailed.orEmpty()
        val id = variants.firstOrNull { it.variantId == key.variantId }?.thirdParty?.tcgplayer
            ?: variants.firstNotNullOfOrNull { it.thirdParty?.tcgplayer }
        memory[k] = id ?: 0
        prefs.edit().putInt(k, id ?: 0).apply()
        return id
    }

    companion object {
        fun thumb(productId: Int) = "https://tcgplayer-cdn.tcgplayer.com/product/${productId}_in_400x400.jpg"
        fun large(productId: Int) = "https://tcgplayer-cdn.tcgplayer.com/product/${productId}_in_1000x1000.jpg"
    }
}
