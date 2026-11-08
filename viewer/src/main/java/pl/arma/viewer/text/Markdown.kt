package pl.arma.viewer.text

/**
 * Prosty parser Markdown (CommonMark w zakresie spotykanym w README i notatkach):
 * nagłówki (# i podkreślane), akapity, cytaty, listy (także zagnieżdżone
 * i z polami wyboru), bloki kodu, linie poziome i tabele GFM. Formatowanie
 * w linii: pogrubienie, kursywa, przekreślenie, `kod`, odnośniki, obrazy (jako
 * opis) i adresy w nawiasach ostrych.
 *
 * Bez zależności od Compose — wynik rysuje [MarkdownView], a parser da się
 * sprawdzić zwykłym testem jednostkowym.
 */
object Markdown {

    sealed interface Block
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Code(val language: String, val text: String) : Block
    data class Quote(val blocks: List<Block>) : Block
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<Item>) : Block
    /** [checked]: null — zwykły punkt, true/false — pole wyboru `[x]` / `[ ]`. */
    data class Item(val blocks: List<Block>, val checked: Boolean?)
    data object Rule : Block
    data class Table(val header: List<String>, val align: List<Align>, val rows: List<List<String>>) : Block

    enum class Align { START, CENTER, END }

    fun parse(text: String): List<Block> = parseLines(text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split('\n'))

    private val ATX = Regex("^ {0,3}(#{1,6})(?:\\s+(.*?))?\\s*$")
    private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})\\s*([^`\\s]*)")
    private val RULE = Regex("^ {0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val BULLET = Regex("^( {0,3})([-*+])(\\s+|$)(.*)")
    private val ORDERED = Regex("^( {0,3})(\\d{1,9})([.)])(\\s+|$)(.*)")
    private val QUOTE = Regex("^ {0,3}> ?(.*)")
    private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
    private val TASK = Regex("^\\[([ xX])]\\s+(.*)", RegexOption.DOT_MATCHES_ALL)

    private fun parseLines(lines: List<String>): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) blocks += Paragraph(paragraph.joinToString("\n") { it.trim() })
            paragraph.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { flush(); i++; continue }

            val fenceMatch = FENCE.find(line)
            if (fenceMatch != null) {
                val match = fenceMatch
                flush()
                val fence = match.groupValues[1]
                val indent = line.indexOfFirst { it != ' ' }
                val body = mutableListOf<String>()
                i++
                while (i < lines.size) {
                    val current = lines[i]
                    if (current.trimStart().startsWith(fence.take(3)) && current.trim().all { it == fence[0] } && current.trim().length >= fence.length) { i++; break }
                    body += current.drop(minOf(indent, current.indexOfFirst { it != ' ' }.let { if (it < 0) current.length else it }))
                    i++
                }
                blocks += Code(match.groupValues[2], body.joinToString("\n"))
                continue
            }

            // Akapit podkreślony === albo --- to nagłówek (zanim --- uznamy za linię).
            if (paragraph.isNotEmpty() && line.trim().let { t -> t.isNotEmpty() && (t.all { it == '=' } || t.all { it == '-' }) }) {
                val level = if (line.trim().first() == '=') 1 else 2
                blocks += Heading(level, paragraph.joinToString(" ") { it.trim() })
                paragraph.clear()
                i++
                continue
            }

            val atx = ATX.find(line)
            if (atx != null) {
                flush()
                blocks += Heading(atx.groupValues[1].length, atx.groupValues[2].replace(Regex("\\s+#+$"), "").trim())
                i++
                continue
            }

            if (RULE.matches(line)) { flush(); blocks += Rule; i++; continue }

            if (QUOTE.containsMatchIn(line)) {
                flush()
                val inner = mutableListOf<String>()
                while (i < lines.size) {
                    val m = QUOTE.find(lines[i])
                    when {
                        m != null -> inner += m.groupValues[1]
                        // Leniwa kontynuacja: zwykła linia dopisuje się do akapitu cytatu.
                        lines[i].isNotBlank() && inner.lastOrNull()?.isNotBlank() == true -> inner += lines[i]
                        else -> break
                    }
                    i++
                }
                blocks += Quote(parseLines(inner))
                continue
            }

            if (BULLET.containsMatchIn(line) || ORDERED.containsMatchIn(line)) {
                // Lista przerywa akapit tylko punktem z treścią (jak w CommonMark).
                val startsList = paragraph.isEmpty() || (BULLET.find(line)?.groupValues?.get(4)?.isNotBlank() ?: false) ||
                    (ORDERED.find(line)?.let { it.groupValues[2] == "1" && it.groupValues[5].isNotBlank() } ?: false)
                if (startsList && !RULE.matches(line)) {
                    flush()
                    i = parseList(lines, i, blocks)
                    continue
                }
            }

            // Tabela: wiersz nagłówka z | i zaraz pod nim wiersz z kreskami.
            if (paragraph.isEmpty() && line.contains('|') && i + 1 < lines.size && TABLE_SEPARATOR.matches(lines[i + 1]) && lines[i + 1].contains('-')) {
                val header = cells(line)
                val align = cells(lines[i + 1]).map { c ->
                    val t = c.trim()
                    when {
                        t.startsWith(":") && t.endsWith(":") -> Align.CENTER
                        t.endsWith(":") -> Align.END
                        else -> Align.START
                    }
                }
                if (align.size == header.size) {
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                        val row = cells(lines[i])
                        rows += List(header.size) { row.getOrElse(it) { "" } }
                        i++
                    }
                    blocks += Table(header, align, rows)
                    continue
                }
            }

            // Kod wcięty czterema spacjami — tylko poza akapitem.
            if (paragraph.isEmpty() && line.startsWith("    ")) {
                val body = mutableListOf<String>()
                while (i < lines.size && (lines[i].startsWith("    ") || lines[i].isBlank())) { body += lines[i].drop(4); i++ }
                blocks += Code("", body.joinToString("\n").trimEnd('\n'))
                continue
            }

            paragraph += line
            i++
        }
        flush()
        return blocks
    }

    /** Czyta listę od wiersza [from]; zwraca indeks pierwszego wiersza za nią. */
    private fun parseList(lines: List<String>, from: Int, out: MutableList<Block>): Int {
        val first = marker(lines[from])!!
        val items = mutableListOf<Item>()
        var i = from
        while (i < lines.size) {
            val m = marker(lines[i]) ?: break
            if (m.ordered != first.ordered || (!m.ordered && m.bullet != first.bullet)) break
            val body = mutableListOf(m.content)
            i++
            var blankBefore = false
            while (i < lines.size) {
                val line = lines[i]
                val indent = line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }
                when {
                    line.isBlank() -> { body += ""; blankBefore = true }
                    indent >= m.contentIndent -> { body += line.drop(m.contentIndent); blankBefore = false }
                    // Kolejny punkt tej samej listy albo coś, co zaczyna nowy blok.
                    marker(line) != null || blankBefore || startsBlock(line) -> break
                    // Leniwa kontynuacja akapitu w punkcie.
                    else -> body += line.trim()
                }
                i++
            }
            // Puste wiersze na końcu punktu należą do odstępu między punktami.
            while (body.size > 1 && body.last().isBlank()) body.removeAt(body.lastIndex)
            val task = TASK.find(body.first())
            val checked = task?.let { it.groupValues[1] != " " }
            if (task != null) body[0] = task.groupValues[2]
            items += Item(parseLines(body), checked)
            // Pusty wiersz, po którym nie ma punktu ani wcięcia, kończy listę.
            if (i < lines.size && lines[i - 1].isBlank() && marker(lines[i]) == null) break
        }
        out += ListBlock(first.ordered, first.number, items)
        return i
    }

    private class Marker(val ordered: Boolean, val bullet: Char, val number: Int, val content: String, val contentIndent: Int)

    private fun marker(line: String): Marker? {
        if (RULE.matches(line)) return null
        BULLET.find(line)?.let { m ->
            val indent = m.groupValues[1].length
            val gap = m.groupValues[3].length.coerceIn(1, 4)
            return Marker(false, m.groupValues[2][0], 1, m.groupValues[4], indent + 1 + gap)
        }
        ORDERED.find(line)?.let { m ->
            val indent = m.groupValues[1].length
            val gap = m.groupValues[4].length.coerceIn(1, 4)
            return Marker(true, m.groupValues[3][0], m.groupValues[2].toIntOrNull() ?: 1, m.groupValues[5], indent + m.groupValues[2].length + 1 + gap)
        }
        return null
    }

    private fun startsBlock(line: String) =
        ATX.matches(line) || FENCE.containsMatchIn(line) || QUOTE.containsMatchIn(line) || RULE.matches(line)

    private fun cells(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.drop(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var code = false
        var k = 0
        while (k < t.length) {
            val c = t[k]
            when {
                c == '\\' && k + 1 < t.length && t[k + 1] == '|' -> { cell.append('|'); k++ }
                c == '`' -> { code = !code; cell.append(c) }
                c == '|' && !code -> { out += cell.toString().trim(); cell.clear() }
                else -> cell.append(c)
            }
            k++
        }
        out += cell.toString().trim()
        return out
    }

    // ——— Formatowanie w linii ———

    /** Kawałek tekstu z jednolitym formatowaniem. [link] — adres odnośnika. */
    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
        val link: String? = null
    )

    private data class Style(val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false, val link: String? = null)

    fun inline(text: String): List<Span> {
        val out = mutableListOf<Span>()
        inline(text, Style(), out)
        // Sąsiednie kawałki o tym samym stylu łączymy — mniej zakresów w tekście.
        val merged = mutableListOf<Span>()
        for (span in out) {
            val last = merged.lastOrNull()
            if (last != null && last.copy(text = "") == span.copy(text = "")) merged[merged.lastIndex] = last.copy(text = last.text + span.text)
            else merged += span
        }
        return merged
    }

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>\""

    private fun inline(text: String, style: Style, out: MutableList<Span>) {
        val plain = StringBuilder()
        fun emit() {
            if (plain.isNotEmpty()) out += Span(plain.toString(), style.bold, style.italic, style.strike, false, style.link)
            plain.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE -> { plain.append(text[i + 1]); i += 2; continue }
                // Twarde złamanie: odwrotny ukośnik albo dwie spacje przed końcem wiersza.
                c == '\\' && i + 1 < text.length && text[i + 1] == '\n' -> { plain.append('\n'); i += 2; continue }
                c == ' ' && text.startsWith("  \n", i) -> {
                    plain.append('\n')
                    i += 3
                    continue
                }
                c == '\n' -> { plain.append(' '); i++; continue }
                c == '`' -> {
                    val ticks = text.drop(i).takeWhile { it == '`' }.length
                    val close = text.indexOf("`".repeat(ticks), i + ticks)
                    if (close > 0) {
                        emit()
                        val code = text.substring(i + ticks, close).replace('\n', ' ').let { if (it.length > 2 && it.startsWith(' ') && it.endsWith(' ')) it.substring(1, it.length - 1) else it }
                        out += Span(code, style.bold, style.italic, style.strike, true, style.link)
                        i = close + ticks
                        continue
                    }
                    plain.append("`".repeat(ticks)); i += ticks; continue
                }
                c == '<' -> {
                    val close = text.indexOf('>', i)
                    val target = if (close > 0) text.substring(i + 1, close) else ""
                    if (close > 0 && (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")) && ' ' !in target) {
                        emit()
                        out += Span(target, style.bold, style.italic, style.strike, false, target)
                        i = close + 1
                        continue
                    }
                }
                c == '!' && text.startsWith("![", i) -> {
                    val found = link(text, i + 1)
                    if (found != null) {
                        // Obrazu nie wczytujemy — zostaje jego opis.
                        emit()
                        inline(found.first, style.copy(italic = true), out)
                        i = found.third
                        continue
                    }
                }
                c == '[' -> {
                    val found = link(text, i)
                    if (found != null) {
                        emit()
                        inline(found.first, style.copy(link = style.link ?: found.second), out)
                        i = found.third
                        continue
                    }
                }
                c == '~' && text.startsWith("~~", i) -> {
                    val close = text.indexOf("~~", i + 2)
                    if (close > i + 2) {
                        emit()
                        inline(text.substring(i + 2, close), style.copy(strike = true), out)
                        i = close + 2
                        continue
                    }
                }
                c == '*' || c == '_' -> {
                    val run = text.drop(i).takeWhile { it == c }.length
                    // `_` wewnątrz słowa (nazwa_pliku) nie formatuje.
                    val intraword = c == '_' && i > 0 && text[i - 1].isLetterOrDigit()
                    val opensBefore = i + run < text.length && !text[i + run].isWhitespace()
                    if (!intraword && opensBefore) {
                        val size = minOf(run, 3)
                        val delimiter = c.toString().repeat(size)
                        val close = findClose(text, i + size, delimiter, c)
                        if (close > 0) {
                            emit()
                            if (run > size) plain.append(c.toString().repeat(run - size))
                            val inner = text.substring(i + size, close)
                            val next = when (size) {
                                1 -> style.copy(italic = true)
                                2 -> style.copy(bold = true)
                                else -> style.copy(bold = true, italic = true)
                            }
                            inline(inner, next, out)
                            i = close + size
                            continue
                        }
                    }
                    plain.append(c.toString().repeat(run)); i += run; continue
                }
            }
            plain.append(c)
            i++
        }
        emit()
    }

    /** Zamknięcie [delimiter]: nie poprzedzone spacją, a dla `_` nie w środku słowa. */
    private fun findClose(text: String, from: Int, delimiter: String, c: Char): Int {
        var k = from
        while (k < text.length) {
            if (text[k] == '\\') { k += 2; continue }
            if (text[k] == '`') {
                val end = text.indexOf('`', k + 1)
                if (end > 0) { k = end + 1; continue }
            }
            if (text[k] == c) {
                // Cała seria znaków naraz: `**` w środku kursywy to zagnieżdżone
                // pogrubienie, a nie zamknięcie kursywy na pierwszej gwiazdce.
                val run = text.drop(k).takeWhile { it == c }.length
                val after = k + run
                val canClose = k > from && !text[k - 1].isWhitespace() &&
                    (c != '_' || after >= text.length || !text[after].isLetterOrDigit())
                if (canClose && run == delimiter.length) return k
                if (canClose && run > delimiter.length && delimiter.length > 1) return k
                k += run
                continue
            }
            k++
        }
        return -1
    }

    /** `[opis](adres "tytuł")` od nawiasu w [start]: opis, adres i indeks za odnośnikiem. */
    private fun link(text: String, start: Int): Triple<String, String, Int>? {
        var depth = 0
        var k = start
        while (k < text.length) {
            when (text[k]) {
                '\\' -> k++
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) break }
            }
            k++
        }
        if (k >= text.length || k + 1 >= text.length || text[k + 1] != '(') return null
        val close = text.indexOf(')', k + 2).takeIf { it > 0 } ?: return null
        val target = text.substring(k + 2, close).trim().let { t ->
            if (t.startsWith("<") && t.contains('>')) t.substring(1, t.indexOf('>')) else t.substringBefore(' ')
        }
        return Triple(text.substring(start + 1, k), target, close + 1)
    }
}
