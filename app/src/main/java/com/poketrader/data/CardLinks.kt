package com.poketrader.data

import java.net.URLEncoder
import java.util.Locale

object CardLinks {
    /** Languages Cardmarket's website is available in. */
    private val siteLanguages = setOf("en", "fr", "de", "es", "it")

    /**
     * The card's page on Cardmarket, in the phone's language when Cardmarket has it.
     * Cardmarket redirects `/<Game>/Products?idProduct=<id>` to the product page, and every variant
     * (reverse holo, Poké Ball pattern…) has its own product id. Without an id, falls back to a
     * name search.
     */
    fun cardmarket(card: CardRef, locale: Locale = Locale.getDefault()): String {
        val lang = locale.language.takeIf { it in siteLanguages } ?: "en"
        val id = card.cardmarketId
        return if (id != null) {
            "https://www.cardmarket.com/$lang/Pokemon/Products?idProduct=$id"
        } else {
            "https://www.cardmarket.com/$lang/Pokemon/Products/Search?searchString=" + URLEncoder.encode(card.name, "UTF-8")
        }
    }
}
