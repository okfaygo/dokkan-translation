package dev.fogo.dokkantranslate.match

import android.content.Context
import dev.fogo.dokkantranslate.util.stringOr
import dev.fogo.dokkantranslate.util.stringOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * The bundled JP-text -> card-id index built by prototype/build_index.py.
 *
 * Each card has TWO key groups because DokkanInfo serves two views per card
 * and which one is the player's current kit varies (bare URL = base kit for
 * EZA'd URs but SEZA kit for LRs; ?eza=true is the other one). The matcher
 * scores both; whichever view matched the screenshot is the kit the player
 * is actually looking at, and the app fetches that same view for display.
 *
 *  - keys:    from the bare jpnja page ("lines" + active + leader)
 *  - altKeys: from the ?eza=true jpnja page ("pre_eza_lines" + its leader)
 */
class CardRecord(
    val id: String,
    val name: String,
    val nameEn: String?,
    val title: String,
    val rarity: Int,
    val element: Int,
    /** max EZA step from the game data; 0 when the card has no EZA.
     *  Passed as &step= on the alt view — required for some transformed
     *  EZA'd LR forms, a no-op everywhere else. */
    val ezaStep: Int,
    /** byte range of this card's kit inside the packed blob */
    val kitSpan: Pair<Int, Int>?,
    /** false when the card exists on the JP server only */
    val onGlobal: Boolean,
    val keys: List<String>,
    val altKeys: List<String>,
) {
    val displayName get() = (nameEn ?: name).replace("\n", " ")
    val idNumber get() = id.toLongOrNull() ?: 0L

    /** Base summonable cards; 4xxxxxxx/9xxxxxxx are transformed/story forms. */
    val isBaseCard get() = idNumber < 4_000_000

    val displayLabel: String
        get() {
            val rarityName = RARITIES[rarity] ?: "?"
            return "[$rarityName ${elementName(element)}] $displayName"
        }

    companion object {
        private val RARITIES = mapOf(2 to "SR", 3 to "SSR", 4 to "UR", 5 to "LR")
        private val ELEMENTS =
            mapOf(0 to "AGL", 1 to "TEQ", 2 to "INT", 3 to "STR", 4 to "PHY")

        fun elementName(code: Int): String {
            val prefix = when (code / 10) {
                1 -> "Super "
                2 -> "Extreme "
                else -> ""
            }
            return prefix + (ELEMENTS[code % 10] ?: "?")
        }
    }
}

object CardIndex {
    @Volatile
    private var cached: List<CardRecord>? = null

    /** Drop the parsed copy so the next load() picks up a fresh download. */
    fun invalidate() {
        synchronized(this) { cached = null }
    }

    fun load(context: Context): List<CardRecord> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            // A downloaded index (kept current by CI) wins over the one
            // baked into the APK, which is only as new as the last build.
            val text = IndexUpdater.downloadedIndex(context)
                ?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
                ?: context.assets.open("index.json")
                    .bufferedReader(Charsets.UTF_8).readText()
            val root = JSONObject(text)
            val records = ArrayList<CardRecord>(root.length())
            for (id in root.keys()) {
                // the index also carries a "__meta__" entry
                if (id.startsWith("__")) continue
                val rec = root.getJSONObject(id)
                val title = rec.stringOr("title")
                val name = rec.stringOr("name")

                val keys = ArrayList<String>()
                keys.addAll(strings(rec.optJSONArray("lines")))
                keys.addAll(strings(rec.optJSONArray("active_lines")))
                keys.addAll(strings(rec.optJSONArray("sa_names")))
                keys.addAll(leaderLines(rec.stringOr("leader")))
                if (title.isNotEmpty()) keys.add(title)
                if (name.isNotEmpty()) keys.add(name)
                rec.stringOrNull("passive_name")?.let { keys.add(it) }
                rec.stringOrNull("active_name")?.let { keys.add(it) }

                val altKeys = ArrayList<String>()
                altKeys.addAll(strings(rec.optJSONArray("pre_eza_lines")))
                altKeys.addAll(leaderLines(rec.stringOr("pre_eza_leader")))

                if (keys.isEmpty() && altKeys.isEmpty()) continue
                records.add(
                    CardRecord(
                        id = id,
                        name = name,
                        nameEn = rec.stringOrNull("name_en"),
                        title = title,
                        rarity = rec.optInt("rarity"),
                        element = rec.stringOr("element").toIntOrNull() ?: -1,
                        ezaStep = rec.optInt("eza_step", 0),
                        kitSpan = rec.optJSONArray("kit")?.let {
                            it.getInt(0) to it.getInt(1)
                        },
                        onGlobal = rec.optBoolean("global", true),
                        keys = keys,
                        altKeys = altKeys,
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

    /** Wrapped display lines PLUS the unwrapped full text — the in-game card
     *  page shows the leader as one truncated line, and containment scoring
     *  matches that against the full string regardless of wrap points. */
    private fun leaderLines(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val lines = text.split("\n")
            .map { it.trim('、', ' ', '　') }
            .filter { it.isNotEmpty() }
        return lines + text.replace("\n", "")
    }
}
