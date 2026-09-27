package com.poketrader.data

import java.util.Locale

object Csv {
    private fun escape(s: String) =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun row(vararg fields: Any?): String = fields.joinToString(",") { f ->
        when (f) {
            null -> ""
            is Double -> String.format(Locale.US, "%.2f", f)
            else -> escape(f.toString())
        }
    }

    /** RFC 4180-ish parser: quoted fields, doubled quotes, CRLF or LF line endings. */
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"'); i++
                    } else quoted = false
                } else field.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row += field.toString(); field.clear() }
                '\r' -> {}
                '\n' -> { row += field.toString(); field.clear(); rows += row; row = mutableListOf() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString(); rows += row
        }
        return rows
    }
}
