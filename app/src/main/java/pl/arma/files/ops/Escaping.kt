package pl.arma.files.ops

/**
 * Escapowanie pól w liniowych formatach zapisu (dziennik kosza, lista kont).
 *
 * Nazwa pliku w Linuksie może zawierać tabulator i nową linię, a hasło —
 * dowolny znak, więc pola trzeba escape'ować, zanim trafią do formatu
 * rozdzielanego tabulatorem.
 */
object Escaping {

    fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val c = value[index]
            if (c == '\\' && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    '\\' -> out.append('\\')
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    else -> out.append(next)
                }
                index += 2
            } else {
                out.append(c)
                index++
            }
        }
        return out.toString()
    }
}
