package com.poketrader.data

/**
 * Splits what was typed in search into the card name and an optional card number after it:
 * "pikachu 86/110" → ("pikachu", "86/110"). Only a separate last word containing a digit counts as
 * the number, so names like "Pikachu V" or "Porygon2" stay whole.
 */
object SearchText {
    private val trailingNumber =
        // The part after "/" may still be empty while typing ("86/").
        Regex("""^(.*?\S)\s+(#?[A-Za-z]{0,5}\d{1,4}[A-Za-z]?(?:\s*/\s*[A-Za-z]{0,5}\d{0,4})?)$""")

    fun split(text: String): Pair<String, String> {
        val t = text.trim()
        val m = trailingNumber.find(t) ?: return t to ""
        return m.groupValues[1].trim() to m.groupValues[2].replace(" ", "")
    }

    /**
     * True if [text] is still about the already chosen [name] — only a number is being typed or edited
     * after it (or nothing). Adding a word like " V" means a different card name.
     */
    fun keepsName(text: String, name: String?): Boolean {
        if (name == null) return false
        val t = text.trimStart()
        if (!t.startsWith(name, ignoreCase = true)) return false
        val rest = t.substring(name.length)
        return rest.isBlank() || (rest.first().isWhitespace() && rest.any { it.isDigit() || it == '#' || it == '/' })
    }
}

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
