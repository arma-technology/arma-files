package pl.arma.files

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Zawartość szuflady zapisana między uruchomieniami aplikacji. */
class ShelfStore(context: Context) {
    private val prefs = context.getSharedPreferences("shelf", Context.MODE_PRIVATE)

    fun load(): List<FileItem> = runCatching {
        val array = JSONArray(prefs.getString("items", "[]"))
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            FileItem(
                uri = Uri.parse(o.getString("uri")),
                name = o.getString("name"),
                mimeType = o.optString("mime", "application/octet-stream"),
                size = o.optLong("size"),
                lastModified = o.optLong("modified"),
                isDirectory = o.optBoolean("dir")
            )
        }
    }.getOrDefault(emptyList())

    fun save(items: List<FileItem>) {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("uri", item.uri.toString())
                    .put("name", item.name)
                    .put("mime", item.mimeType)
                    .put("size", item.size)
                    .put("modified", item.lastModified)
                    .put("dir", item.isDirectory)
            )
        }
        prefs.edit().putString("items", array.toString()).apply()
    }
}
