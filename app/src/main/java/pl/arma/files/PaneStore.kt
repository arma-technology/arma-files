package pl.arma.files

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Karty z ostatniego uruchomienia: katalog każdej karty, jej historia „wstecz”
 * i która była aktywna. Dzięki temu aplikacja otwiera się tam, gdzie ją
 * zostawiono, a nie zawsze na ekranie „Moje urządzenie”.
 */
class PaneStore(context: Context) {
    private val prefs = context.getSharedPreferences("panes", Context.MODE_PRIVATE)
    private var last: String? = null

    /** Miejsce: [dir] == null to ekran główny. */
    data class Place(val root: Uri?, val source: SourceType, val dir: Uri?)
    data class SavedPane(val id: String, val place: Place, val history: List<Place>)
    data class Saved(val panes: List<SavedPane>, val activeId: String)

    fun load(): Saved? = runCatching {
        val o = JSONObject(prefs.getString("state", null) ?: return null)
        val array = o.getJSONArray("panes")
        val panes = List(array.length()) { i ->
            val p = array.getJSONObject(i)
            val history = p.optJSONArray("history") ?: JSONArray()
            SavedPane(p.getString("id"), place(p), List(history.length()) { place(history.getJSONObject(it)) })
        }
        if (panes.isEmpty()) null else Saved(panes, o.optString("active", panes.first().id))
    }.getOrNull()

    fun save(state: Saved) {
        val array = JSONArray()
        state.panes.forEach { pane ->
            val history = JSONArray()
            // Historia przycięta — po restarcie wystarczy kilka kroków wstecz.
            pane.history.takeLast(HISTORY).forEach { history.put(json(it)) }
            array.put(json(pane.place).put("id", pane.id).put("history", history))
        }
        val text = JSONObject().put("panes", array).put("active", state.activeId).toString()
        if (text == last) return
        last = text
        prefs.edit().putString("state", text).apply()
    }

    private fun json(place: Place) = JSONObject()
        .put("root", place.root?.toString())
        .put("source", place.source.name)
        .put("dir", place.dir?.toString())

    private fun place(o: JSONObject) = Place(
        root = o.optString("root").takeIf { it.isNotEmpty() && it != "null" }?.let(Uri::parse),
        source = runCatching { SourceType.valueOf(o.optString("source")) }.getOrDefault(SourceType.LOCAL),
        dir = o.optString("dir").takeIf { it.isNotEmpty() && it != "null" }?.let(Uri::parse)
    )

    private companion object {
        const val HISTORY = 20
    }
}
