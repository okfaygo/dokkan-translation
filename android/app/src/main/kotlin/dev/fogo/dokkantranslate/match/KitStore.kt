package dev.fogo.dokkantranslate.match

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * One super attack, as extracted at build time.
 *
 * Dokkan gates these on Ki, and the numeric effect values live apart from
 * the prose — "Raises ATK for 4 turns" never says by how much — so the
 * scraper resolves both and stores the result.
 */
data class SuperAttack(
    val name: String,
    val description: String,
    val kiRequired: Int,
    val style: String,
    val condition: String?,
    /** rendered effects carrying {passiveImg:...} icon tokens */
    val effects: List<String>,
) {
    val label: String
        get() = when {
            kiRequired >= 18 -> "Ultra Super Attack · $kiRequired Ki"
            kiRequired > 0 -> "Super Attack · $kiRequired Ki"
            else -> "Super Attack"
        }
}

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
    val supers: List<SuperAttack>,
    val activeName: String,
    val activeDesc: String,
    val links: List<String>,
    val categories: List<String>,
    /** other forms of this card: (card id, name) */
    val transformations: List<Pair<String, String>>,
)

class CardNotOnGlobalException(cardId: String) :
    Exception("This card isn't on the Global server yet (id $cardId), so no English kit exists for it.")

class KitUnavailableException(cardId: String) :
    Exception("No kit stored for card $cardId. The card index may be out of date — reopen the app to refresh it.")

/**
 * English kits, read from the packed blob that ships with the index.
 *
 * Previously the app fetched a ~211KB card page per card at runtime. Only
 * ~1.9KB of it was ever used, and every user paid that cost against a fan
 * site's bandwidth, so extraction moved to the scraper. What arrives here
 * is a concatenation of small JSON objects; [CardRecord.kitSpan] says
 * where each one starts.
 *
 * The blob ships gzipped (~1MB in the APK rather than ~9.5MB) and is
 * inflated once on first use, because a compressed asset cannot be seeked.
 */
object KitStore {

    private const val ASSET = "kits.json.gz"
    private const val UNPACKED = "kits.bin"

    @Volatile
    private var ready: File? = null

    /** The inflated blob, unpacking it on first use. */
    private fun blob(context: Context): File {
        ready?.let { if (it.exists()) return it }
        synchronized(this) {
            ready?.let { if (it.exists()) return it }
            val target = File(context.filesDir, UNPACKED)
            val source = IndexUpdater.downloadedKits(context)
            val stamp = File(context.filesDir, "$UNPACKED.stamp")
            val want = source?.lastModified()?.toString() ?: "asset"

            if (!target.exists() || stamp.takeIf { it.exists() }?.readText() != want) {
                val temp = File(context.filesDir, "$UNPACKED.part")
                val input = source?.inputStream() ?: context.assets.open(ASSET)
                input.use { raw ->
                    GZIPInputStream(raw).use { gz ->
                        temp.outputStream().use { out -> gz.copyTo(out) }
                    }
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                stamp.writeText(want)
            }
            ready = target
            return target
        }
    }

    /** Drop the inflated copy so a newer download is picked up. */
    fun invalidate() {
        synchronized(this) { ready = null }
    }

    fun kit(context: Context, record: CardRecord): Kit {
        if (!record.onGlobal) throw CardNotOnGlobalException(record.id)
        val span = record.kitSpan ?: throw KitUnavailableException(record.id)
        val bytes = ByteArray(span.second)
        RandomAccessFile(blob(context), "r").use { file ->
            file.seek(span.first.toLong())
            file.readFully(bytes)
        }
        return parse(record.id, JSONObject(String(bytes, Charsets.UTF_8)))
    }

    private fun parse(cardId: String, o: JSONObject): Kit {
        val passive = ArrayList<Pair<Boolean, String>>()
        o.optJSONArray("passive")?.let { rows ->
            for (i in 0 until rows.length()) {
                val row = rows.getJSONArray(i)
                passive.add(row.getBoolean(0) to row.getString(1))
            }
        }

        val supers = ArrayList<SuperAttack>()
        o.optJSONArray("supers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val sa = arr.getJSONObject(i)
                supers.add(
                    SuperAttack(
                        name = sa.optString("name"),
                        description = sa.optString("desc"),
                        kiRequired = sa.optInt("ki"),
                        style = sa.optString("style"),
                        condition = sa.optString("condition").ifEmpty { null }
                            ?.takeIf { it != "null" },
                        effects = strings(sa.optJSONArray("effects")),
                    )
                )
            }
        }

        val forms = ArrayList<Pair<String, String>>()
        o.optJSONArray("transformations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val pair = arr.getJSONArray(i)
                forms.add(pair.getString(0) to pair.getString(1))
            }
        }

        return Kit(
            cardId = cardId,
            title = o.optString("title"),
            name = o.optString("name"),
            rarity = o.optString("rarity"),
            element = o.optString("element"),
            leader = o.optString("leader"),
            passiveName = o.optString("passive_name"),
            passiveRows = passive,
            supers = supers,
            activeName = o.optString("active_name"),
            activeDesc = o.optString("active"),
            links = strings(o.optJSONArray("links")),
            categories = strings(o.optJSONArray("categories")),
            transformations = forms,
        )
    }

    private fun strings(arr: JSONArray?): List<String> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }
}
