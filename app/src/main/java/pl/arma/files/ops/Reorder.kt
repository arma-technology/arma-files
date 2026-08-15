package pl.arma.files.ops

/**
 * Przeniesienie elementu listy na inną pozycję. Wydzielone z ViewModelu, bo
 * kolejność indeksów po `removeAt` jest łatwa do pomylenia, a efekt widzi
 * użytkownik przy każdym przeciągnięciu kafelka.
 */
object Reorder {
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val result = list.toMutableList()
        result.add(to, result.removeAt(from))
        return result
    }
}
