package dev.fogo.dokkantranslate.match

import android.content.Context
import org.json.JSONObject

/**
 * The bundled JP-text -> card-id index built by prototype/build_index.py.
 * One record per card id; `keys` is everything an OCR line can match against
 * (passive lines, pre-EZA lines, active skill lines, title, name).
 */
class CardRecord(
    val id: String,
    val name: String,
    val title: String,
    val keys: List<String>,
)

object CardIndex {
    @Volatile
    private var cached: List<CardRecord>? = null

    fun load(context: Context): List<CardRecord> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val text = context.assets.open("index.json")
                .bufferedReader(Charsets.UTF_8).readText()
            val root = JSONObject(text)
            val records = ArrayList<CardRecord>(root.length())
            for (id in root.keys()) {
                val rec = root.getJSONObject(id)
                val title = rec.optString("title", "")
                val name = rec.optString("name", "")
                val keys = ArrayList<String>()
                for (field in listOf("lines", "pre_eza_lines", "active_lines")) {
                    val arr = rec.optJSONArray(field) ?: continue
                    for (i in 0 until arr.length()) keys.add(arr.getString(i))
                }
                if (title.isNotEmpty()) keys.add(title)
                if (name.isNotEmpty()) keys.add(name)
                if (keys.isEmpty()) continue
                records.add(CardRecord(id, name, title, keys))
            }
            cached = records
            return records
        }
    }
}
