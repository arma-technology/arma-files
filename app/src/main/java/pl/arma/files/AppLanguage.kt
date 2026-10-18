package pl.arma.files

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Język aplikacji niezależny od języka telefonu.
 *
 * Od Androida 13 wybór należy do systemu (LocaleManager) — widać go też
 * w ustawieniach telefonu, a system sam podmienia zasoby całego procesu.
 * Na starszych wersjach zapamiętujemy go sami i podstawiamy w kontekście
 * aplikacji oraz aktywności.
 */
object AppLanguage {

    /** Kod BCP 47 i nazwa w tym języku — nazwy nie tłumaczymy, żeby każdy znalazł swój. */
    class Option(val tag: String, val name: String)

    val options = listOf(
        Option("en", "English"),
        Option("pl", "Polski"),
        Option("sq", "Shqip"),
        Option("be", "Беларуская"),
        Option("bs", "Bosanski"),
        Option("bg", "Български"),
        Option("hr", "Hrvatski"),
        Option("cs", "Čeština"),
        Option("nl", "Nederlands"),
        Option("et", "Eesti"),
        Option("de", "Deutsch"),
        Option("hu", "Magyar"),
        Option("lv", "Latviešu"),
        Option("lt", "Lietuvių"),
        Option("mk", "Македонски"),
        Option("ro", "Română"),
        Option("ru", "Русский"),
        Option("sr", "Српски"),
        Option("sk", "Slovenčina"),
        Option("sl", "Slovenščina"),
        Option("uk", "Українська")
    )

    private const val PREFS = "app_language"
    private const val KEY = "tag"

    /** Wybrany język albo null, gdy aplikacja idzie za językiem telefonu. */
    fun current(context: Context): String? =
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales
                .takeUnless { it.isEmpty }?.get(0)?.toLanguageTag()
        } else {
            stored(context)
        }

    private fun stored(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun set(activity: Activity, tag: String?) {
        if (Build.VERSION.SDK_INT >= 33) {
            // System sam odtwarza aktywność z nowymi zasobami.
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
            applyToProcess(activity.applicationContext)
            activity.recreate()
        }
    }

    /** Kontekst z wybranym językiem — dla `attachBaseContext` na Androidzie < 13. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = stored(base) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    /**
     * Teksty spoza ekranów (błędy serwerów, powiadomienia) biorą zasoby
     * z kontekstu aplikacji; na Androidzie < 13 trzeba go przestawić ręcznie.
     */
    @Suppress("DEPRECATION")
    fun applyToProcess(app: Context) {
        if (Build.VERSION.SDK_INT >= 33) return
        val locale = stored(app)?.let(Locale::forLanguageTag) ?: LocaleList.getDefault()[0]
        Locale.setDefault(locale)
        val resources = app.resources
        val config = Configuration(resources.configuration).apply { setLocale(locale) }
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}
