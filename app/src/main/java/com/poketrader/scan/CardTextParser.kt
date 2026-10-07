package com.poketrader.scan

/** One OCR'd line with its bounding box in upright image coordinates. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
    val height get() = bottom - top
}

data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** What we could read off a card. */
data class ScanClues(
    val name: String?,
    /** Printed card number, e.g. "25", "TG05", "SV001". */
    val number: String?,
    /** Printed set size after the slash, e.g. 198 in "025/198". */
    val total: Int?,
    /** Set code printed on newer cards: "SVI" (international) or "SV2a" (Japanese). */
    val setCode: String?,
    /** Language code printed next to the set code on newer international cards ("EN", "FR"…), or "JA", "KO", "ZH". */
    val language: String?,
    val japanese: Boolean,
    /**
     * Printed like a Japanese card ("S6a 015/069") but not in Japanese: a Korean or Chinese print.
     * Those share the Japanese sets' codes and numbers, so they're looked up there. Since 1.12.
     */
    val asian: Boolean = false,
) {
    /** Japanese, Korean and Chinese cards are looked up in TCGdex's Japanese data. */
    val dataLang get() = if (japanese || asian) "ja" else "en"

    val hasAnything get() = name != null || number != null
}

/** The on-screen frame the card should be aligned to; shared by the overlay and the analyzer. */
object ScanGuide {
    const val CARD_ASPECT = 63f / 88f

    fun boxFor(width: Float, height: Float): Box {
        var gw = width * 0.82f
        var gh = gw / CARD_ASPECT
        if (gh > height * 0.94f) {
            gh = height * 0.94f
            gw = gh * CARD_ASPECT
        }
        val l = (width - gw) / 2
        val t = (height - gh) / 2
        return Box(l, t, l + gw, t + gh)
    }
}

object CardTextParser {
    // "025/198", "TG05/TG30", "SV001/SV122", "4/102". Sword & Shield cards print the regulation
    // mark right before the number ("D136/189"), so a letter prefix only counts if the total has one too.
    private val numberRe = Regex("""(?<!\d)([A-Z]{0,3})(\d{1,3}[a-z]?)\s*/\s*([A-Z]{0,3})(\d{2,3})(?!\d)""")

    // "SVI EN", "PAL FR", "OBF DE" — a space (or dot) is required so words don't match.
    private val setLangRe = Regex("""(?<![A-Z0-9])([A-Z]{2,4}\d?(?:\.\d)?)\s*[•·.]?\s+(EN|FR|DE|IT|ES|PT)(?![A-Z])""")

    // Japanese cards print the set code just before the number: "SV2a 025/165", "S6a E 015/069" (with
    // the regulation mark in between). Older international promos and TCG Classic print one too ("CLV 017/034").
    private val jpSetRe = Regex("""(?<![A-Za-z0-9])([A-Za-z]{1,3}\d{0,2}[a-zA-Z]?)(?:\s+[A-H])?\s+[A-Z]{0,3}\d{1,3}\s*/""")

    // Japanese-style set codes: S6a, s10b, SV4a, SM12a. OCR reads the 6 as "b" now and then ("Sba").
    private val asianCodeRe = Regex("""^[A-H]?(?:SV|SM|S)[0-9bO]{1,2}[a-zA-Z]?$""", RegexOption.IGNORE_CASE)

    // The rule box at the bottom of ex / V / GX cards names the kind of card, in every language
    // ("Pokémon ex rule", "Règle des Pokémon-ex", "ポケモンex"…). OCR reads it far more reliably than
    // the stylised "ex" logo next to the name.
    private val ruleRe = Regex("""(?:pok[eé]mon|ポケモン)\s*-?\s*(ex|EX|GX|VSTAR|VMAX|V)(?![A-Za-z])|(?<![A-Za-z])(ex|GX|VSTAR|VMAX|V)\s*-?\s*(?:rule|regel|règle|regola|regla|regra)""", RegexOption.IGNORE_CASE)

    private val hpRe = Regex("""(?i)\bHP\s*\d+|\d+\s*HP\b""")

    // Stage / card-type labels that sit next to the name in several languages.
    private val labelWords = setOf(
        "BASIC", "STAGE", "BASE", "BASIS", "BÁSICO", "BASICO", "FASE", "PHASE", "NIVEAU", "LIVELLO", "ESTÁGIO",
        "TRAINER", "DRESSEUR", "TRAINERKARTE", "ALLENATORE", "ENTRENADOR", "TREINADOR",
        "SUPPORTER", "ITEM", "STADIUM", "ENERGY", "VSTAR", "VMAX",
    )

    fun parse(lines: List<OcrLine>, guide: Box): ScanClues {
        val padX = guide.width * 0.08f
        val padY = guide.height * 0.06f
        val area = Box(guide.left - padX, guide.top - padY, guide.right + padX, guide.bottom + padY)
        val inside = lines.filter { area.contains(it.cx, it.cy) }

        val top = inside.filter { it.cy < guide.top + guide.height * 0.17f }
        // Kana, not just kanji: energy symbols are sometimes read as stray kanji on any card.
        val japanese = top.sumOf { l -> l.text.count { it in '぀'..'ヿ' } } >= 2
        // The name is the biggest text at the top of the card (stage label and HP are smaller or stripped).
        val name = top.mapNotNull { l -> cleanName(l.text)?.let { it to l } }
            .maxByOrNull { (n, l) -> l.height * 1000 + n.length }?.first

        val bottom = inside.filter { it.cy > guide.top + guide.height * 0.82f }.sortedByDescending { it.top }

        var number: String? = null
        var total: Int? = null
        var numberLine: String? = null
        for (l in bottom) {
            val t = normalizeDigits(l.text)
            val m = numberRe.find(t) ?: continue
            number = if (m.groupValues[3].isNotEmpty()) m.groupValues[1] + m.groupValues[2] else m.groupValues[2]
            total = m.groupValues[4].toIntOrNull()
            numberLine = t
            break
        }

        // "ex" / "V"… from the rule box, added to the name when the logo next to it wasn't read.
        val suffix = bottom.firstNotNullOfOrNull { l -> ruleRe.find(l.text)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } } }?.let(::suffixCase)
        val fullName = name?.let { n -> if (suffix == null || n.replace(" ", "").endsWith(suffix, ignoreCase = true)) n else if (japanese) n + suffix else "$n $suffix" }

        var setCode: String? = null
        var language: String? = null
        // Set code + language sit in the bottom-left corner; elsewhere it's flavour text.
        for (l in bottom.filter { it.cx < guide.left + guide.width * 0.45f && it.cy > guide.top + guide.height * 0.86f }) {
            val m = setLangRe.find(l.text.uppercase()) ?: continue
            if (m.groupValues[1].any { it.isLetter() }) {
                setCode = m.groupValues[1]
                language = m.groupValues[2]
                break
            }
        }
        val printedCode = numberLine?.let { jpSetRe.find(it)?.groupValues?.get(1) }
        // A Japanese-style code without kana: Korean (the OCR can't read Hangul) or Chinese.
        val asian = !japanese && setCode == null && printedCode != null && asianCodeRe.matches(printedCode) && languageFromLabels(bottom) == null
        if (japanese || asian) {
            language = when {
                japanese -> "JA"
                top.any { l -> l.text.any { it in '一'..'鿿' } } -> "ZH"
                else -> "KO"
            }
            setCode = printedCode
        } else {
            if (printedCode != null && printedCode.uppercase() in printLanguages) {
                if (language == null) language = printedCode.uppercase()
            } else if (setCode == null) {
                setCode = printedCode?.takeIf { c -> c.length >= 2 && c.any { it.isUpperCase() } }
            }
            if (language == null) language = languageFromLabels(bottom)
        }
        return ScanClues(fullName, number, total, setCode, language, japanese, asian)
    }

    private val printLanguages = setOf("EN", "FR", "DE", "IT", "ES", "PT")

    private fun suffixCase(s: String) = when (s.uppercase()) {
        "EX" -> if (s == "EX") "EX" else "ex"
        else -> s.uppercase()
    }

    /**
     * How likely an OCR'd Japanese-style set code is [setId]: 1 for the same code, less for a near
     * miss ("Sba" for S6a, "GS4s" for SV4a with the regulation mark glued on).
     */
    fun codeSimilarity(code: String, setId: String): Double {
        fun norm(s: String) = s.uppercase().replace('B', '6').replace('O', '0')
        val c = code.trim()
        val tries = listOf(c, c.drop(1)).filter { it.length >= 2 }
        val base = tries.maxOf { t -> similarity(norm(t), norm(setId)).let { s -> if (t.equals(setId, ignoreCase = true)) 1.0 else s } }
        // A regulation mark glued to the code tells the era: G and later are Scarlet & Violet ("SV…"),
        // D to F Sword & Shield ("S…").
        val mark = c.firstOrNull()?.uppercaseChar()?.takeIf { c.length >= 4 && c[1].equals('S', ignoreCase = true) }
        val sv = setId.startsWith("SV", ignoreCase = true)
        val bonus = when (mark) {
            in 'G'..'J' -> if (sv) 0.2 else 0.0
            in 'D'..'F' -> if (!sv) 0.2 else 0.0
            else -> 0.0
        }
        return if (base >= 1.0) 1.0 else minOf(0.99, base + bonus)
    }

    // Weakness / retreat labels are printed in the card's language and OCR reads them reliably.
    private val labelLanguages = listOf(
        "EN" to listOf("weakness", "retreat"),
        "FR" to listOf("faiblesse", "retraite"),
        "DE" to listOf("schwäche", "schwache", "rückzug", "ruckzug"),
        "IT" to listOf("debolezza", "ritirata"),
        "ES" to listOf("debilidad", "retirada"),
        "PT" to listOf("fraqueza", "recuar"),
    )

    private fun languageFromLabels(lines: List<OcrLine>): String? {
        val text = lines.joinToString(" ") { it.text.lowercase() }
        return labelLanguages.firstOrNull { (_, words) -> words.any { it in text } }?.first
    }

    fun containsJapanese(s: String) = s.any { it in '぀'..'ヿ' || it in '一'..'鿿' }

    /** Strips HP, stage labels, digits and symbols; returns null if too little is left to be a name. */
    fun cleanName(raw: String): String? {
        val noHp = hpRe.replace(raw, " ")
        if (containsJapanese(noHp)) {
            val jp = noHp.filter { it.isLetter() || it == 'ー' || it == '・' }
                .replace("たね", "").replace("進化", "").trim()
            return jp.takeIf { it.count(Char::isLetter) >= 2 }
        }
        val kept = noHp.map { c -> if (c.isLetter() || c in " '’-.♀♂") c else ' ' }.joinToString("")
        val words = kept.split(' ').filter { it.isNotBlank() && it.uppercase() !in labelWords }
            .filter { w -> w.any(Char::isLetter) }
        // Lone letters are usually OCR noise from energy symbols — except the "V" of V cards.
        val cleaned = words.dropLastWhile { it.length == 1 && it != "V" }.dropWhile { it.length == 1 }
        val name = cleaned.joinToString(" ").trim(' ', '-', '.', '\'')
        if (name.startsWith("Evolves", true) || name.startsWith("Évolue", true) || name.startsWith("Entwickelt", true)) return null
        return name.takeIf { n -> n.count(Char::isLetter) >= 3 }
    }

    /** OCR often reads the zeros of "025" as the letter O; fix tokens that are clearly numbers. */
    fun normalizeDigits(text: String): String = text.split(' ').joinToString(" ") { tok ->
        if (tok.count { it.isDigit() } >= 1 && tok.all { it.isDigit() || it in "oOIl/" })
            tok.map { c -> when (c) { 'o', 'O' -> '0'; 'I', 'l' -> '1'; else -> c } }.joinToString("")
        else tok
    }

    /** 0..1 similarity of two names, ignoring case, spaces and punctuation. */
    fun similarity(a: String, b: String): Double {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val d = IntArray(y.length + 1) { it }
        for (i in 1..x.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..y.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (x[i - 1] == y[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return 1.0 - d[y.length].toDouble() / maxOf(x.length, y.length)
    }
}
