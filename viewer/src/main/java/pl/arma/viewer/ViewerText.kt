package pl.arma.viewer

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes

/**
 * Teksty dla kodu bez dostępu do kontekstu (opisy adnotacji PDF, uwagi
 * rysunku). Ustawiane przy otwarciu dokumentu; kontekst aplikacji, więc
 * zmiana języka aplikacji zmienia też je.
 */
internal object ViewerText {
    @Volatile
    private var resources: Resources? = null

    fun init(context: Context) {
        resources = context.applicationContext.resources
    }

    fun get(@StringRes id: Int, vararg args: Any): String =
        resources?.getString(id, *args) ?: error("ViewerText.init was not called")
}
