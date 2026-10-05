package dev.fogo.dokkantranslate.match

import android.content.Context
import dev.fogo.dokkantranslate.util.stringOr
import dev.fogo.dokkantranslate.util.stringOrNull
import org.json.JSONObject

/**
 * One mission, in both languages. Built by prototype/build_missions.py from
 * DokkanInfo's JP and Global mission pages, joined on the mission id the
 * two servers share — so the English is Global's official text, not a
 * machine translation.
 */
class MissionRecord(
    val id: String,
    val category: String,
    /** JP text keeps the game's own line breaks: they are where the OCR
     *  lines break */
    val nameJa: String,
    val descJa: String,
    /** null when the mission isn't on Global (yet) */
    val nameEn: String?,
    val descEn: String?,
) {
    val idNumber get() = id.toLongOrNull() ?: 0L
}

/** A key string points at missions, or at a whole event (category name).
 *  A data class on purpose: the same mission reached through two keys must
 *  be ONE map entry in the matcher, as the (id, category) tuple is in
 *  match_missions.py. */
data class MissionRef(val mission: MissionRecord?, val category: String)

/**
 * The bundled mission table plus the lookup structures the matcher needs.
 *
 * Keys mirror prototype/match_missions.py: each name and description in
 * full, each of their lines, and each event name (also without its 【tag】,
 * which the in-game banner drops).
 *
 * [postings] is a character -> (key, count) inverted index. The shared
 * count of characters bounds the LCS from above, so most of the ~13k keys
 * are ruled out per OCR segment without running an LCS at all.
 */
class MissionIndex private constructor(
    val missions: List<MissionRecord>,
    val keys: Array<String>,
    val refs: Array<List<MissionRef>>,
    /** key spans several screen lines (only these are tried for a pair) */
    val multiline: BooleanArray,
    val postings: Map<Char, Pair<IntArray, IntArray>>,
) {
    /** Newest mission id per event. Ties go to the NEWEST: reruns repeat an
     *  event's missions word for word, and the player is looking at the
     *  one running now. */
    val categoryNewest: Map<String, Long> = HashMap<String, Long>().also { m ->
        for (rec in missions) m[rec.category] = maxOf(m[rec.category] ?: 0L, rec.idNumber)
    }

    companion object {
        private const val ASSET = "missions.json"
        /** shortest single name/description line used as a key */
        const val MIN_PART = 6

        @Volatile
        private var cached: MissionIndex? = null

        fun invalidate() {
            synchronized(this) { cached = null }
        }

        fun load(context: Context): MissionIndex {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                // a downloaded table (kept current by CI) wins over the APK's
                val text = IndexUpdater.downloadedMissions(context)
                    ?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
                    ?: context.assets.open(ASSET)
                        .bufferedReader(Charsets.UTF_8).readText()
                return build(JSONObject(text)).also { cached = it }
            }
        }

        private fun build(root: JSONObject): MissionIndex {
            val missions = ArrayList<MissionRecord>(root.length())
            val byKey = LinkedHashMap<String, LinkedHashSet<Pair<MissionRecord?, String>>>()
            val multi = HashSet<String>()

            fun add(key: String, mission: MissionRecord?, category: String) {
                byKey.getOrPut(key) { LinkedHashSet() }.add(mission to category)
            }

            for (id in root.keys()) {
                if (id.startsWith("__")) continue   // __meta__, __cats__
                val o = root.getJSONObject(id)
                val rec = MissionRecord(
                    id = id,
                    category = o.optLong("c").toString(),
                    nameJa = o.stringOr("n"),
                    descJa = o.stringOr("d"),
                    nameEn = o.stringOrNull("en"),
                    descEn = o.stringOrNull("ed"),
                )
                if (rec.nameJa.isEmpty()) continue
                missions.add(rec)
                for (text in listOf(rec.nameJa, rec.descJa)) {
                    if (text.isEmpty()) continue
                    val joined = text.replace("\n", "")
                    add(joined, rec, rec.category)
                    if ('\n' in text) multi.add(joined)
                    for (part in text.split("\n")) {
                        if (part.length >= MIN_PART) add(part, rec, rec.category)
                    }
                }
            }

            root.optJSONObject("__cats__")?.let { cats ->
                for (cat in cats.keys()) {
                    val name = cats.getString(cat)
                    add(name, null, cat)
                    val tail = name.substringAfter('】', name).trim()
                    if (tail != name && tail.length >= MIN_PART) add(tail, null, cat)
                }
            }

            val keys = byKey.keys.toTypedArray()
            val refs = Array(keys.size) { i ->
                byKey.getValue(keys[i]).map { (m, c) -> MissionRef(m, c) }
            }
            val multiline = BooleanArray(keys.size) { keys[it] in multi }

            val keyLists = HashMap<Char, ArrayList<Int>>()
            val countLists = HashMap<Char, ArrayList<Int>>()
            for ((k, key) in keys.withIndex()) {
                for ((ch, n) in charCounts(key)) {
                    keyLists.getOrPut(ch) { ArrayList() }.add(k)
                    countLists.getOrPut(ch) { ArrayList() }.add(n)
                }
            }
            val postings = keyLists.mapValues { (ch, ks) ->
                ks.toIntArray() to countLists.getValue(ch).toIntArray()
            }
            return MissionIndex(missions, keys, refs, multiline, postings)
        }

        fun charCounts(s: String): Map<Char, Int> {
            val counts = HashMap<Char, Int>()
            for (ch in s) counts[ch] = (counts[ch] ?: 0) + 1
            return counts
        }
    }
}
