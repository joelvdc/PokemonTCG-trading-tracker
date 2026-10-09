package com.poketrader.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the collection can be sorted by, with each order's natural direction first. Since 1.11. */
enum class SortField(val label: String, val forward: String, val backward: String) {
    NAME("Name", "A to Z", "Z to A"),
    SET("Set", "A to Z", "Z to A"),
    NUMBER("Set and number", "Low to high", "High to low"),
    RARITY("Rarity", "Rarest first", "Common first"),
    VALUE("Value per card", "Highest first", "Lowest first"),
    RECENT("Date added", "Newest first", "Oldest first"),
    QUANTITY("Copies", "Most first", "Fewest first"),
}

@Serializable
data class SortLevel(val field: SortField, val reversed: Boolean = false)

/** Sorting in layers: by the first level, then the next among equals, and so on. Since 1.11. */
@Serializable
data class SortSpec(val levels: List<SortLevel> = listOf(SortLevel(SortField.NAME))) {
    fun encode(): String = json.encodeToString(serializer(), this)

    fun comparator(priceType: PriceType): Comparator<CollectionRow> {
        val parts = levels.map { level ->
            val c = fieldComparator(level.field, priceType)
            if (level.reversed) c.reversed() else c
        }
        // Name breaks remaining ties, so the order never depends on the database.
        return (parts + compareBy(String.CASE_INSENSITIVE_ORDER) { it.item.card.name }).reduce { a, b -> a.then(b) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(s: String?): SortSpec = s?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
            ?.takeIf { it.levels.isNotEmpty() } ?: SortSpec()

        private fun number(n: String) = n.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE

        fun fieldComparator(f: SortField, priceType: PriceType): Comparator<CollectionRow> = when (f) {
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.item.card.name }
            SortField.SET -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.item.card.setName }
            SortField.NUMBER -> compareBy<CollectionRow, String>(String.CASE_INSENSITIVE_ORDER) { it.item.card.setName }.thenBy { number(it.item.card.localId) }
            SortField.RARITY -> compareByDescending<CollectionRow> { Rarities.rank(it.item.card.rarity) }
            SortField.VALUE -> compareByDescending { it.unitPrice(priceType) ?: -1.0 }
            SortField.RECENT -> compareByDescending { it.item.addedAt }
            SortField.QUANTITY -> compareByDescending { it.item.quantity }
        }
    }
}

/** Pokémon TCG rarities, roughly from common to the rarest, for sorting. Since 1.11. */
object Rarities {
    /** Higher is rarer; unknown rarities sit in the middle. */
    fun rank(rarity: String): Int {
        val r = rarity.lowercase()
        return when {
            r.isBlank() || r == "none" -> 0
            "special illustration" in r -> 90
            // "Futuristic Rare" is the 30th Celebration set's top card (Mew ex).
            "hyper" in r || "gold" in r || "secret" in r || "crown" in r || "futuristic" in r -> 95
            "illustration" in r -> 80
            "shiny" in r -> 75
            "ultra" in r || "full art" in r -> 70
            // "Pikachu Rare": 30th Celebration's Pikachu cards, a step above a plain Rare.
            "ace" in r || "double" in r || "amazing" in r || "radiant" in r || "pikachu" in r -> 60
            " v" in r || "vmax" in r || "vstar" in r || " ex" in r || " gx" in r || "break" in r || "prime" in r || "legend" in r -> 55
            "holo" in r -> 40
            "promo" in r -> 35
            "rare" in r -> 30
            "uncommon" in r -> 20
            "common" in r -> 10
            else -> 50
        }
    }
}

/** International (English/European) or Japanese prints. */
enum class PrintFilter(val label: String) { ANY("All prints"), INTERNATIONAL("International prints"), JAPANESE("Japanese prints") }

/**
 * The collection's filter button: the things awkward to type in the search field. All set parts
 * must match; within a part, any of the chosen values does. Since 1.11.
 */
@Serializable
data class CollectionFilter(
    val rarities: Set<String> = emptySet(),
    /** Set ids. */
    val sets: Set<String> = emptySet(),
    /** Version labels, e.g. "Reverse Holo". */
    val variants: Set<String> = emptySet(),
    val conditions: Set<String> = emptySet(),
    val languages: Set<String> = emptySet(),
    val print: PrintFilter = PrintFilter.ANY,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
) {
    val isEmpty get() = this == CollectionFilter()

    val count: Int
        get() = listOf(rarities.isNotEmpty(), sets.isNotEmpty(), variants.isNotEmpty(), conditions.isNotEmpty(), languages.isNotEmpty(),
            print != PrintFilter.ANY, minPrice != null || maxPrice != null).count { it }

    fun encode(): String = json.encodeToString(serializer(), this)

    fun matches(row: CollectionRow, priceType: PriceType): Boolean {
        val i = row.item
        val card = i.card
        if (rarities.isNotEmpty() && card.rarity !in rarities) return false
        if (sets.isNotEmpty() && card.setId !in sets) return false
        if (variants.isNotEmpty() && card.variantLabel !in variants) return false
        if (conditions.isNotEmpty() && i.condition !in conditions) return false
        if (languages.isNotEmpty() && i.language !in languages) return false
        when (print) {
            PrintFilter.ANY -> {}
            PrintFilter.INTERNATIONAL -> if (card.isJapanese) return false
            PrintFilter.JAPANESE -> if (!card.isJapanese) return false
        }
        if (minPrice != null || maxPrice != null) {
            val p = row.unitPrice(priceType) ?: return false
            if (minPrice != null && p < minPrice) return false
            if (maxPrice != null && p > maxPrice) return false
        }
        return true
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(s: String?): CollectionFilter =
            s?.takeIf { it.isNotEmpty() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: CollectionFilter()
    }
}
