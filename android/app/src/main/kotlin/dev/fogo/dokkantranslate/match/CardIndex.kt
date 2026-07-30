package dev.fogo.dokkantranslate.match

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The bundled JP-text -> card-id index built by prototype/build_index.py.
 *
 * `keys` is everything an OCR line can match against: passive lines (current
 * kit AND previous-EZA-step kit — in-game text depends on how far the player
 * has awakened the card, so both stay in the match pool), active skill lines,
 * leader lines (the one plain-font element on the card page itself), title
 * and name. Display always shows the current kit; the pre-EZA lines exist
 * for matching only.
 */
class CardRecord(
    val id: String,
    val name: String,
    val nameEn: String?,
    val title: String,
    val rarity: Int,
    val keys: List<String>,
) {
    val displayName get() = (nameEn ?: name).replace("\n", " ")
    val idNumber get() = id.toLongOrNull() ?: 0L
}

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
                keys.addAll(strings(rec.optJSONArray("lines")))
                keys.addAll(strings(rec.optJSONArray("pre_eza_lines")))
                keys.addAll(strings(rec.optJSONArray("active_lines")))
                keys.addAll(leaderLines(rec.optString("leader", "")))
                keys.addAll(leaderLines(rec.optString("pre_eza_leader", "")))
                if (title.isNotEmpty()) keys.add(title)
                if (name.isNotEmpty()) keys.add(name)
                if (keys.isEmpty()) continue

                records.add(
                    CardRecord(
                        id = id,
                        name = name,
                        nameEn = rec.optString("name_en", "").ifEmpty { null },
                        title = title,
                        rarity = rec.optInt("rarity"),
                        keys = keys,
                    )
                )
            }
            cached = records
            return records
        }
    }

    fun findById(context: Context, id: String): CardRecord? =
        load(context).firstOrNull { it.id == id }

    private fun strings(arr: JSONArray?): List<String> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun leaderLines(text: String): List<String> =
        text.split("\n")
            .map { it.trim('、', ' ', '　') }
            .filter { it.isNotEmpty() }
}
