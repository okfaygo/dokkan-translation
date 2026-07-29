package dev.fogo.dokkantranslate.api

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** English kit, parsed from dokkan.wiki (port of prototype/dokkan_api.py). */
class Kit(
    val cardId: String,
    val title: String,
    val name: String,
    val rarity: String,
    val element: String,
    val leader: String,
    val passiveName: String,
    /** (isHeader, text) rows of the itemized passive */
    val passiveRows: List<Pair<Boolean, String>>,
    val supers: List<Pair<String, String>>,
    val links: List<String>,
    val categories: List<String>,
)

object DokkanWiki {

    private const val UA =
        "DokkanTranslate/0.1 (identify-and-lookup helper; github.com/okfaygo/dokkan-translation)"
    private val RARITIES = mapOf(3 to "SSR", 4 to "UR", 5 to "LR")
    private val ELEMENTS = mapOf(0 to "AGL", 1 to "TEQ", 2 to "INT", 3 to "STR", 4 to "PHY")

    /** Fetch a card's kit, using a permanent on-disk cache. */
    fun fetch(context: Context, cardId: String): Kit {
        val dir = File(context.cacheDir, "kits").apply { mkdirs() }
        val cacheFile = File(dir, "$cardId.json")
        val text = if (cacheFile.exists()) {
            cacheFile.readText(Charsets.UTF_8)
        } else {
            val body = httpGet("https://dokkan.wiki/api/cards/$cardId")
            cacheFile.writeText(body, Charsets.UTF_8)
            body
        }
        return parse(cardId, JSONObject(text))
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode != 200) {
                throw RuntimeException("dokkan.wiki HTTP ${conn.responseCode} for $url")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun clean(text: String): String =
        text.replace(Regex("\\{passiveImg:[^}]+\\}"), "").trim()

    private fun elementName(code: Int): String {
        val prefix = when (code / 10) {
            1 -> "Super "
            2 -> "Extreme "
            else -> ""
        }
        return prefix + (ELEMENTS[code % 10] ?: "?$code")
    }

    private fun parse(cardId: String, payload: JSONObject): Kit {
        val card = payload.getJSONObject("card")

        val passiveRows = ArrayList<Pair<Boolean, String>>()
        for (raw in clean(card.optString("passive_skill_itemized_desc")).split("\n")) {
            val row = raw.trim()
            if (row.isEmpty()) continue
            if (row.startsWith("*") && row.endsWith("*")) {
                passiveRows.add(true to row.trim('*'))
            } else {
                passiveRows.add(false to clean(row))
            }
        }

        val supers = ArrayList<Pair<String, String>>()
        payload.optJSONArray("specials")?.let { arr ->
            for (i in 0 until arr.length()) {
                val sp = arr.getJSONObject(i)
                supers.add(
                    sp.optString("name") to
                        clean(sp.optString("description")).replace("\n", " ")
                )
            }
        }

        fun names(field: String): List<String> {
            val arr = payload.optJSONArray(field) ?: return emptyList()
            return (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }
        }

        return Kit(
            cardId = cardId,
            title = card.optString("title"),
            name = card.optString("name"),
            rarity = RARITIES[card.optInt("rarity")] ?: card.optInt("rarity").toString(),
            element = elementName(card.optInt("element")),
            leader = clean(card.optString("leader_skill", "-")),
            passiveName = card.optString("passive_skill_name"),
            passiveRows = passiveRows,
            supers = supers,
            links = names("card_links"),
            categories = names("categories"),
        )
    }
}
