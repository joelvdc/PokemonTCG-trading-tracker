package com.poketrader.data

/**
 * Filters search results by the number printed on the card: "86", "#86", "86/110", "086",
 * "TG05" or "/110" (set size only). Leading zeros don't matter, and the part after the slash
 * matches the set's printed size.
 */
data class NumberFilter(val number: String?, val total: Int?) {
    val isEmpty get() = number == null && total == null

    fun matches(localId: String, setOfficial: Int?, setTotal: Int?): Boolean {
        if (number != null && normalize(localId) != number) return false
        if (total != null && total != setOfficial && total != setTotal) return false
        return true
    }

    companion object {
        val NONE = NumberFilter(null, null)

        fun parse(input: String): NumberFilter {
            val s = input.trim().removePrefix("#").replace(" ", "")
            if (s.isEmpty()) return NONE
            val number = s.substringBefore('/').takeIf { it.isNotEmpty() }?.let(::normalize)
            val total = if ('/' in s) s.substringAfter('/').filter(Char::isDigit).toIntOrNull() else null
            return NumberFilter(number, total)
        }

        /** "086" → "86", "TG05" → "TG5", "swsh039" → "SWSH39". */
        fun normalize(id: String): String =
            id.uppercase().replace(Regex("(?<![0-9])0+(?=[0-9])"), "")
    }
}
