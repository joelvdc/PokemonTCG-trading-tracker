package com.poketrader.data

import java.util.Calendar

/** Count cards (copies) or their value in the charts. */
enum class StatMode(val label: String) { CARDS("Cards"), VALUE("Value") }

/**
 * Opens the collection showing exactly some cards: the filter-button [filter], the search box's
 * [search] text and the binder ([binder] null = all cards). Since 1.13.
 */
data class CollectionJump(val filter: CollectionFilter = CollectionFilter(), val search: String = "", val binder: Long? = null)

/**
 * One slice or bar: its [label], how many copies and what they're worth, and the cards it stands
 * for ([jump], null when they can't be shown as a filter). [detail] and [fraction] are for set
 * completion ("120/132", 0.91).
 */
data class StatEntry(
    val key: String,
    val label: String,
    val copies: Int,
    val value: Double,
    val jump: CollectionJump? = null,
    val detail: String? = null,
    val fraction: Double? = null,
) {
    fun amount(mode: StatMode): Double = if (mode == StatMode.CARDS) copies.toDouble() else value
}

/** A card worth mentioning, e.g. the most valuable ones. */
data class StatCard(val row: CollectionRow, val note: String)

/** Where a set sits in TCGdex's series ("Scarlet & Violet", "Mega Evolution"…): the [order] the series came out in. */
data class Era(val name: String, val order: Int)

data class CollectionStatsResult(
    val copies: Int,
    val uniqueNames: Int,
    val printings: Int,
    val value: Double,
    val unpriced: Int,
    /** Copies with a purchase price: what they cost and what they're worth now. */
    val paidCopies: Int,
    val paid: Double,
    val paidNowWorth: Double,
    val rarities: List<StatEntry>,
    val completion: List<StatEntry>,
    val topSets: List<StatEntry>,
    val eras: List<StatEntry>,
    val kinds: List<StatEntry>,
    val pokemon: List<StatEntry>,
    val priceRanges: List<StatEntry>,
    val versions: List<StatEntry>,
    val prints: List<StatEntry>,
    val languages: List<StatEntry>,
    val conditions: List<StatEntry>,
    val binders: List<StatEntry>,
    /** Copies added per month, the last 12 months, oldest first. */
    val added: List<StatEntry>,
    val mostValuable: List<StatCard>,
) {
    val averageValue get() = if (copies > 0) value / copies else 0.0
}

/** Collection statistics for the stats screen, chosen for Pokémon collecting. Since 1.13. */
object CollectionStats {
    /** Price ranges for one copy, in EUR: [from, to). */
    val PRICE_RANGES = listOf(0.0 to 1.0, 1.0 to 5.0, 5.0 to 20.0, 20.0 to 50.0, 50.0 to 100.0, 100.0 to Double.MAX_VALUE)

    // The kind of card a name ends in ("Pikachu ex", "Charizard-GX", "Lugia ◇", Japanese "ピカチュウex").
    private val suffixRe = Regex("""(?:[\s\-]|(?<=[^\x00-\x7F]))(VMAX|VSTAR|V-UNION|V|GX|EX|ex|BREAK|LV\.X|◇|☆)$""")
    private val KIND_NAMES = linkedMapOf(
        "Mega ex" to "Mega Pokémon ex", "Mega EX" to "Mega Pokémon-EX", "ex" to "Pokémon ex", "EX" to "Pokémon-EX",
        "GX" to "Pokémon-GX", "V" to "Pokémon V", "VMAX" to "Pokémon VMAX", "VSTAR" to "Pokémon VSTAR",
        "V-UNION" to "Pokémon V-UNION", "Radiant" to "Radiant Pokémon", "BREAK" to "Pokémon BREAK", "LV.X" to "Pokémon LV.X",
        "◇" to "Prism Star", "☆" to "Pokémon Star",
    )

    private fun isMega(name: String) = name.startsWith("Mega ") || name.startsWith("M ") || name.startsWith("メガ")
    private fun isRadiant(name: String) = name.startsWith("Radiant ") || name.startsWith("かがやく")

    /** "Pokémon ex", "Mega Pokémon ex", "Radiant Pokémon"…, or null for a regular card. */
    fun kind(name: String): String? {
        val n = name.trim()
        val suffix = suffixRe.find(n)?.groupValues?.get(1)
        val key = when {
            isMega(n) && (suffix == "ex" || suffix == "EX") -> "Mega $suffix"
            isRadiant(n) -> "Radiant"
            else -> suffix
        }
        return key?.let { KIND_NAMES[it] }
    }

    /** The Pokémon a card shows: "Mega Lucario ex" → "Lucario", "Pikachu V" → "Pikachu". */
    fun baseName(name: String): String {
        var n = name.trim()
        n = suffixRe.replace(n, "").trim()
        for (p in listOf("Mega ", "M ", "Radiant ", "メガ", "かがやく")) if (n.startsWith(p) && n.length > p.length + 1) n = n.removePrefix(p).trim()
        return n.trimEnd('-', ' ')
    }

    fun compute(
        rows: List<CollectionRow>,
        priceType: PriceType,
        binderNames: Map<Long, String>,
        /** "lang/setId" → its era; sets missing here count as "Other". */
        eras: Map<String, Era>,
        binder: Long?,
        now: Long = System.currentTimeMillis(),
    ): CollectionStatsResult {
        fun price(r: CollectionRow) = r.unitPrice(priceType) ?: 0.0
        fun value(r: CollectionRow) = price(r) * r.item.quantity
        fun entry(key: String, label: String, rs: List<CollectionRow>, jump: CollectionJump? = null) =
            StatEntry(key, label, rs.sumOf { it.item.quantity }, rs.sumOf(::value), jump)
        fun jump(filter: CollectionFilter = CollectionFilter(), search: String = "") = CollectionJump(filter, search, binder)

        val rarities = rows.groupBy { it.item.card.rarity.ifBlank { "None" } }
            .map { (r, rs) -> entry(r, r, rs, if (r == "None") null else jump(CollectionFilter(rarities = setOf(r)))) }
            .sortedWith(compareByDescending<StatEntry> { Rarities.rank(it.key) }.thenBy { it.label })

        val bySet = rows.groupBy { it.item.card.dataLang to it.item.card.setId }
        val completion = bySet.mapNotNull { (_, rs) ->
            val card = rs.first().item.card
            val official = card.setOfficial?.takeIf { it > 0 } ?: return@mapNotNull null
            val numbers = rs.mapNotNull { it.item.card.localId.toIntOrNull() }.toSet()
            val inSet = numbers.count { it in 1..official }
            val extra = numbers.count { it > official }
            entry(card.setId, setLabel(card), rs, jump(CollectionFilter(sets = setOf(card.setId)))).copy(
                detail = "$inSet/$official" + if (extra > 0) " +$extra" else "",
                fraction = inSet.toDouble() / official,
            )
        }.sortedWith(compareByDescending<StatEntry> { it.fraction }.thenByDescending { it.copies })

        val topSets = bySet.map { (_, rs) ->
            val card = rs.first().item.card
            entry(card.setId, setLabel(card), rs, jump(CollectionFilter(sets = setOf(card.setId))))
        }

        val eraGroups = rows.groupBy { r -> eras["${r.item.card.dataLang}/${r.item.card.setId}"] }
        val erasList = eraGroups.map { (era, rs) ->
            val sets = rs.map { it.item.card.setId }.toSet()
            entry(era?.name ?: "Other", era?.name ?: "Other", rs, jump(CollectionFilter(sets = sets)))
        }.sortedBy { e -> eraGroups.keys.firstOrNull { (it?.name ?: "Other") == e.key }?.order ?: Int.MAX_VALUE }

        val kinds = rows.groupBy { kind(it.item.card.name) }.filterKeys { it != null }
            .map { (k, rs) -> entry(k!!, k, rs) }
            .sortedByDescending { it.copies }

        val pokemon = rows.filter { !it.item.card.name.contains("Energy", ignoreCase = true) && !it.item.card.name.contains("エネルギー") }
            .groupBy { baseName(it.item.card.name) }
            .map { (n, rs) -> entry(n, n, rs, jump(search = n)) }
            .sortedWith(compareByDescending<StatEntry> { it.copies }.thenByDescending { it.value })

        val priceRanges = PRICE_RANGES.map { (lo, hi) ->
            val rs = rows.filter { r -> r.unitPrice(priceType)?.let { it >= lo && it < hi } == true }
            val label = when {
                lo == 0.0 -> "Under ${euros(hi)}"
                hi == Double.MAX_VALUE -> "${euros(lo)} and up"
                else -> "${euros(lo)} – ${euros(hi)}"
            }
            entry(label, label, rs, jump(CollectionFilter(minPrice = lo.takeIf { it > 0 }, maxPrice = if (hi == Double.MAX_VALUE) null else hi - 0.005)))
        }

        val versions = rows.groupBy { it.item.card.variantLabel }
            .map { (v, rs) -> entry(v, v.ifBlank { "Normal" }, rs, jump(CollectionFilter(variants = setOf(v)))) }
            .sortedByDescending { it.copies }

        val (japanese, international) = rows.partition { it.item.card.isJapanese }
        val prints = listOf(
            entry("en", "International prints", international, jump(CollectionFilter(print = PrintFilter.INTERNATIONAL))),
            entry("ja", "Japanese prints", japanese, jump(CollectionFilter(print = PrintFilter.JAPANESE))),
        ).filter { it.copies > 0 }

        val languages = rows.groupBy { it.item.language }
            .map { (l, rs) -> entry(l, LANGUAGES.firstOrNull { it.first == l }?.second ?: l, rs, jump(CollectionFilter(languages = setOf(l)))) }
            .sortedByDescending { it.copies }
        val conditions = rows.groupBy { it.item.condition }
            .map { (k, rs) -> entry(k, CONDITIONS.firstOrNull { it.first == k }?.second ?: k, rs, jump(CollectionFilter(conditions = setOf(k)))) }
            .sortedBy { e -> CONDITIONS.indexOfFirst { it.first == e.key }.let { if (it < 0) Int.MAX_VALUE else it } }
        val binders = rows.groupBy { it.item.binderId }
            .map { (b, rs) -> entry(b.toString(), binderNames[b] ?: Binder.UNSORTED_NAME, rs, CollectionJump(binder = b)) }
            .sortedByDescending { it.value }

        val months = (11 downTo 0).map { back ->
            Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.DAY_OF_MONTH, 1)
                add(Calendar.MONTH, -back)
            }.let { it.get(Calendar.YEAR) to it.get(Calendar.MONTH) }
        }
        val addedBy = rows.groupBy { r -> Calendar.getInstance().apply { timeInMillis = r.item.addedAt }.let { it.get(Calendar.YEAR) to it.get(Calendar.MONTH) } }
        val added = months.map { (y, m) ->
            val label = Calendar.getInstance().apply { clear(); set(y, m, 1) }
                .getDisplayName(Calendar.MONTH, Calendar.SHORT, java.util.Locale.getDefault()) ?: "${m + 1}"
            entry("$y-${m + 1}", label, addedBy[y to m].orEmpty())
        }

        val paidRows = rows.filter { it.item.purchasePrice != null }
        return CollectionStatsResult(
            copies = rows.sumOf { it.item.quantity },
            uniqueNames = rows.map { it.item.card.name.lowercase() }.toSet().size,
            printings = rows.map { it.item.card.cardId to it.item.card.variantId }.toSet().size,
            value = rows.sumOf(::value),
            unpriced = rows.filter { it.unitPrice(priceType) == null }.sumOf { it.item.quantity },
            paidCopies = paidRows.sumOf { it.item.quantity },
            paid = paidRows.sumOf { it.item.purchasePrice!! * it.item.quantity },
            paidNowWorth = paidRows.sumOf(::value),
            rarities = rarities,
            completion = completion,
            topSets = topSets,
            eras = erasList,
            kinds = kinds,
            pokemon = pokemon,
            priceRanges = priceRanges,
            versions = versions,
            prints = prints,
            languages = languages,
            conditions = conditions,
            binders = binders,
            added = added,
            mostValuable = rows.sortedByDescending(::price).take(10).map { StatCard(it, "${it.item.quantity}×") },
        )
    }

    private fun setLabel(card: CardRef) = card.setName.ifBlank { card.setId } + if (card.isJapanese) " (JP)" else ""

    private fun euros(v: Double) = Money.format(v, decimals = 0)
}
