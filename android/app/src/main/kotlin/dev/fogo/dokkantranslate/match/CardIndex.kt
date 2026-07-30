package dev.fogo.dokkantranslate.match

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The bundled JP-text -> card-id index built by prototype/build_index.py.
 *
 * Keys are split into two groups so the matcher can tell WHICH state of an
 * EZA'd card the screenshot shows:
 *  - postKeys: current kit (post-EZA if the card is EZA'd) + title + name
 *              + leader lines (leader text is the one plain-font element on
 *              the card page itself, not just the passive popup)
 *  - preKeys:  the original pre-EZA kit lines, empty for non-EZA'd cards
 */
class CardRecord(
    val id: String,
    val name: String,
    val nameEn: String?,
    val title: String,
    val postKeys: List<String>,
    val preKeys: List<String>,
) {
    val hasPreEza get() = preKeys.isNotEmpty()
    val displayName get() = (nameEn ?: name).replace("\n", " ")
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

                val post = ArrayList<String>()
                post.addAll(strings(rec.optJSONArray("lines")))
                post.addAll(strings(rec.optJSONArray("active_lines")))
                post.addAll(leaderLines(rec.optString("leader", "")))
                if (title.isNotEmpty()) post.add(title)
                if (name.isNotEmpty()) post.add(name)

                val pre = ArrayList<String>()
                pre.addAll(strings(rec.optJSONArray("pre_eza_lines")))
                pre.addAll(leaderLines(rec.optString("pre_eza_leader", "")))

                if (post.isEmpty() && pre.isEmpty()) continue
                records.add(
                    CardRecord(
                        id = id,
                        name = name,
                        nameEn = rec.optString("name_en", "").ifEmpty { null },
                        title = title,
                        postKeys = post,
                        preKeys = pre,
                    )
                )
            }
            cached = records
            return records
        }
    }

    private fun strings(arr: JSONArray?): List<String> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun leaderLines(text: String): List<String> =
        text.split("\n")
            .map { it.trim('、', ' ', '　') }
            .filter { it.isNotEmpty() }
}
