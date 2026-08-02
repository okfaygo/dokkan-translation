package dev.fogo.dokkantranslate.api

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * English kit source: the GLOBAL dokkaninfo.com card page, which embeds the
 * full kit as HTML-entity-escaped JSON in a `datajson="..."` attribute.
 * Always up to date (JP/GLB release simultaneously), same card ids as the
 * bundled index.
 *
 * DokkanInfo serves two views per card and which one is the player's
 * current kit varies by card (bare URL = base kit for EZA'd URs but SEZA
 * kit for LRs; ?eza=true is the other one). `altView` picks the ?eza=true
 * view; the caller passes whichever view MATCHED the screenshot, so the
 * displayed kit is the one on the player's screen. No pre/post-EZA
 * labeling exists on purpose — the views aren't consistently either.
 */
data class Kit(
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
    val activeName: String,
    val activeDesc: String,
    val links: List<String>,
    val categories: List<String>,
    /** other forms of this card: (card id, name) */
    val transformations: List<Pair<String, String>>,
)

class CardNotOnGlobalException(cardId: String) :
    Exception("This card isn't on the Global server yet (id $cardId), so no English kit exists for it.")

object DokkanInfo {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) DokkanTranslate/0.2 (identify-and-lookup helper)"
    private val RARITIES = mapOf(2 to "SR", 3 to "SSR", 4 to "UR", 5 to "LR")
    private val ELEMENTS = mapOf(0 to "AGL", 1 to "TEQ", 2 to "INT", 3 to "STR", 4 to "PHY")
    private val PASSIVE_IMG = Regex("\\{passiveImg:[^}]+\\}")

    @Volatile
    private var legacyCachePurged = false

    /**
     * Fetch a card's English kit, using a permanent on-disk cache.
     *
     * ezaStep is the card's max EZA step; it's appended as &step= on the
     * alt view because plain ?eza=true serves the wrong (untransformed)
     * kit for some transformed EZA'd LR forms, while &step=<max> is a
     * verified no-op where ?eza=true already worked.
     */
    fun fetch(
        context: Context,
        cardId: String,
        altView: Boolean = false,
        ezaStep: Int = 0,
    ): Kit {
        purgeLegacyCache(context)
        val dir = File(context.cacheDir, "dokkaninfo").apply { mkdirs() }
        // step-aware name: entries cached before &step= support are never read
        val cacheFile =
            File(dir, if (altView) "$cardId-alt$ezaStep.json" else "$cardId.json")
        val text = if (cacheFile.exists()) {
            cacheFile.readText(Charsets.UTF_8)
        } else {
            var url = "https://dokkaninfo.com/cards/$cardId"
            if (altView) {
                url += "?eza=true"
                if (ezaStep > 0) url += "&step=$ezaStep"
            }
            val body = try {
                httpGet(url)
            } catch (e: FileNotFoundException) {
                throw CardNotOnGlobalException(cardId)
            }
            val json = extractDataJson(body)
            cacheFile.writeText(json, Charsets.UTF_8)
            json
        }
        val kit = parse(cardId, JSONObject(text))

        // A transformed form's card page serves the BASE card's EZA passive —
        // the form's own EZA kit exists only behind the transformation API
        // (the endpoint DokkanInfo's own transformation arrows call). Leader,
        // categories and the form list on the page are already correct, so
        // only the form-specific parts are overlaid. Best-effort: any failure
        // leaves the page kit untouched.
        if (altView && ezaStep > 0 && isTransformedForm(cardId)) {
            runCatching { overlayFormKit(context, dir, cardId, ezaStep, kit) }
                .getOrNull()
                ?.let { return it }
        }
        return kit
    }

    /** 4xxxxxxx / 9xxxxxxx ids are transformed or story forms, not base cards. */
    private fun isTransformedForm(cardId: String): Boolean =
        (cardId.toLongOrNull() ?: 0L) >= 4_000_000

    private fun overlayFormKit(
        context: Context,
        dir: File,
        cardId: String,
        ezaStep: Int,
        kit: Kit,
    ): Kit {
        val cacheFile = File(dir, "$cardId-tf$ezaStep.json")
        val text = if (cacheFile.exists()) {
            cacheFile.readText(Charsets.UTF_8)
        } else {
            httpGet(
                "https://dokkaninfo.com/api/cards/$cardId/transformation" +
                    "?eza=true&step=$ezaStep"
            ).also { cacheFile.writeText(it, Charsets.UTF_8) }
        }
        val api = JSONObject(text)
        val passive = api.optJSONObject("passive_skill")
        val supers = parseSupers(api)
        val links = namesOf(api, "links")
        return kit.copy(
            passiveName = passive?.optString("name")?.ifEmpty { null } ?: kit.passiveName,
            passiveRows = passive?.optString("itemized_description")
                ?.ifEmpty { null }
                ?.let { parseItemized(it) } ?: kit.passiveRows,
            supers = supers.ifEmpty { kit.supers },
            links = links.ifEmpty { kit.links },
        )
    }

    /**
     * v0.1 cached dokkan.wiki payloads under cacheDir/kits/<id>.json — the
     * same filenames this source uses. Reading those with this parser gave
     * blank kits (field report slide 1), so nuke the old dir once.
     */
    private fun purgeLegacyCache(context: Context) {
        if (legacyCachePurged) return
        legacyCachePurged = true
        File(context.cacheDir, "kits").deleteRecursively()
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        try {
            when (conn.responseCode) {
                200 -> return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                404 -> throw FileNotFoundException(url)
                else -> throw RuntimeException("dokkaninfo HTTP ${conn.responseCode} for $url")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Pull the datajson attribute out of the page and HTML-unescape it. */
    private fun extractDataJson(html: String): String {
        val marker = "datajson=\""
        val start = html.indexOf(marker)
        if (start < 0) throw RuntimeException("no datajson found in card page")
        val from = start + marker.length
        val end = html.indexOf('"', from)
        if (end < 0) throw RuntimeException("unterminated datajson attribute")
        return html.substring(from, end)
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&") // must be last
    }

    private fun clean(text: String): String = PASSIVE_IMG.replace(text, "").trim()

    private fun elementName(code: Int): String {
        val prefix = when (code / 10) {
            1 -> "Super "
            2 -> "Extreme "
            else -> ""
        }
        return prefix + (ELEMENTS[code % 10] ?: "?$code")
    }

    /**
     * Itemized passive -> display rows. Headers are wrapped in *...* and may
     * span several lines; items start with "- " (EN) or "・" (JP); anything
     * else continues the previous row (the source wraps sentences mid-way).
     *
     * {passiveImg:...} tokens are KEPT — the UI renders them as the in-game
     * icons (see PassiveIcons in the ui package).
     */
    fun parseItemized(text: String): List<Pair<Boolean, String>> {
        val rows = ArrayList<Pair<Boolean, String>>()
        var headerOpen = false
        for (raw in text.trim().split("\n")) {
            val line = raw.trim(' ', '　')
            if (line.isEmpty()) continue
            when {
                headerOpen -> {
                    val closes = line.endsWith("*")
                    append(rows, line.trim('*').trim())
                    if (closes) headerOpen = false
                }
                line.startsWith("*") -> {
                    val closes = line.length > 1 && line.endsWith("*")
                    rows.add(true to line.trim('*').trim())
                    headerOpen = !closes
                }
                line.startsWith("-") || line.startsWith("・") ->
                    rows.add(false to line.trimStart('-', '・', ' ', '　'))
                rows.isEmpty() -> rows.add(false to line)
                else -> append(rows, line)
            }
        }
        return rows
    }

    private fun append(rows: ArrayList<Pair<Boolean, String>>, text: String) {
        val last = rows.removeAt(rows.size - 1)
        rows.add(last.first to (last.second + " " + text))
    }

    private fun parse(cardId: String, data: JSONObject): Kit {
        val card = data.getJSONObject("card")
        val leader = data.optJSONObject("leader_skill")
        val passive = data.optJSONObject("passive_skill")
        val active = data.optJSONObject("active_skill")

        val supers = parseSupers(data)
        val transformations = ArrayList<Pair<String, String>>()
        data.optJSONArray("transformations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val form = arr.getJSONObject(i)
                val id = form.optLong("id").toString()
                if (id == cardId) continue
                val name = form.optString("name").replace("\n", " ")
                if (name.isNotEmpty()) transformations.add(id to name)
            }
        }

        val activeDesc = listOfNotNull(
            active?.optString("effect_description")?.ifEmpty { null },
            active?.optString("condition_description")?.ifEmpty { null },
        ).joinToString(" — ") { clean(it).replace("\n", " ") }

        return Kit(
            cardId = cardId,
            title = leader?.optString("name") ?: "",
            name = card.optString("name").replace("\n", " "),
            rarity = RARITIES[card.optInt("rarity")] ?: card.optInt("rarity").toString(),
            element = elementName(card.optString("element").toIntOrNull() ?: -1),
            leader = clean(leader?.optString("description") ?: "-").replace("\n", " "),
            passiveName = passive?.optString("name") ?: "",
            passiveRows = parseItemized(passive?.optString("itemized_description") ?: ""),
            supers = supers,
            activeName = active?.optString("name") ?: "",
            activeDesc = activeDesc,
            links = namesOf(data, "links"),
            categories = namesOf(data, "categories"),
            transformations = transformations,
        )
    }

    private fun parseSupers(data: JSONObject): List<Pair<String, String>> {
        val supers = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        data.optJSONArray("super_attacks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val attack = arr.getJSONObject(i).optJSONObject("attack") ?: continue
                val name = attack.optString("name")
                val desc = clean(attack.optString("description")).replace("\n", " ")
                if (name.isNotEmpty() && seen.add("$name|$desc")) {
                    supers.add(name to desc)
                }
            }
        }
        return supers
    }

    private fun namesOf(data: JSONObject, field: String): List<String> {
        val arr = data.optJSONArray(field) ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }
    }
}
