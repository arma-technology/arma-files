package pl.arma.files

import java.text.Normalizer
import java.util.Locale

/**
 * Dopasowanie nazwy do frazy: bez wielkości liter i polskich znaków
 * („zolw” znajdzie „Żółw.txt”), a każde słowo frazy musi wystąpić w nazwie.
 */
class NameMatcher(query: String) {
    private val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map(::fold)

    val isEmpty: Boolean get() = terms.isEmpty()

    fun matches(name: String): Boolean {
        if (terms.isEmpty()) return false
        val folded = fold(name)
        return terms.all { it in folded }
    }

    companion object {
        private val marks = Regex("\\p{Mn}+")

        fun fold(text: String): String =
            marks.replace(Normalizer.normalize(text.replace('ł', 'l').replace('Ł', 'L'), Normalizer.Form.NFD), "")
                .lowercase(Locale.ROOT)
    }
}
