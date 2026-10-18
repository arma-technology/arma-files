package pl.arma.viewer.cad

/** Zamiana tekstów z DXF (kody %%, MTEXT z formatowaniem) na zwykły tekst. */
object DxfText {

    /** „\U+0105” → „ą”. Sekwencje \M+ (wielobajtowe strony kodowe) pomijamy. */
    fun unescapeUnicode(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            if (raw.startsWith("\\U+", i) && i + 7 <= raw.length) {
                val hex = raw.substring(i + 3, i + 7).toIntOrNull(16)
                if (hex != null) {
                    out.append(hex.toChar())
                    i += 7
                    continue
                }
            }
            if (raw.startsWith("\\M+", i) && i + 8 <= raw.length) {
                out.append('?')
                i += 8
                continue
            }
            out.append(raw[i])
            i++
        }
        return out.toString()
    }

    /** Kody sterujące jednowierszowego tekstu: %%c → Ø, %%d → °, %%p → ±. */
    fun specialCodes(text: String): String {
        if (text.indexOf("%%") < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text.startsWith("%%", i) && i + 2 < text.length) {
                val c = text[i + 2]
                when (c.lowercaseChar()) {
                    'c' -> out.append('Ø')
                    'd' -> out.append('°')
                    'p' -> out.append('±')
                    '%' -> out.append('%')
                    'u', 'o', 'k' -> Unit
                    else -> if (c.isDigit()) {
                        var j = i + 2
                        while (j < text.length && j < i + 5 && text[j].isDigit()) j++
                        text.substring(i + 2, j).toIntOrNull()?.let { out.append(it.toChar()) }
                        i = j
                        continue
                    } else {
                        out.append("%%").append(c)
                    }
                }
                i += 3
                continue
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    /**
     * MTEXT bez formatowania: akapity jako znaki nowej linii, ułamki „\S1/2;”
     * jako „1/2”, a zmiany czcionki, koloru i wysokości usunięte.
     */
    fun mtextPlain(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        val n = raw.length
        while (i < n) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < n -> {
                    val k = raw[i + 1]
                    when (k) {
                        'P', 'X' -> { out.append('\n'); i += 2 }
                        'N' -> { out.append('\n'); i += 2 }
                        '~' -> { out.append(' '); i += 2 }
                        '\\', '{', '}' -> { out.append(k); i += 2 }
                        'L', 'l', 'O', 'o', 'K', 'k' -> i += 2
                        'S' -> {
                            val end = raw.indexOf(';', i + 2).let { if (it < 0) n else it }
                            out.append(
                                raw.substring(i + 2, end).replace('^', '/').replace('#', '/').trim()
                            )
                            i = end + 1
                        }
                        'U' -> if (raw.startsWith("\\U+", i) && i + 7 <= n) {
                            raw.substring(i + 3, i + 7).toIntOrNull(16)?.let { out.append(it.toChar()) }
                            i += 7
                        } else i += 2
                        'f', 'F', 'H', 'h', 'C', 'c', 'T', 't', 'Q', 'q', 'W', 'w', 'A', 'a', 'p' -> {
                            val end = raw.indexOf(';', i + 2)
                            i = if (end < 0) n else end + 1
                        }
                        else -> { out.append(k); i += 2 }
                    }
                }
                c == '{' || c == '}' -> i++
                c == '^' && i + 1 < n -> {
                    // „^I” to tabulator, „^J” nowa linia, „^ ” sam daszek.
                    when (raw[i + 1]) {
                        'I' -> out.append('\t')
                        'J', 'M' -> out.append('\n')
                        ' ' -> out.append('^')
                        else -> Unit
                    }
                    i += 2
                }
                else -> { out.append(c); i++ }
            }
        }
        return specialCodes(out.toString())
    }
}
