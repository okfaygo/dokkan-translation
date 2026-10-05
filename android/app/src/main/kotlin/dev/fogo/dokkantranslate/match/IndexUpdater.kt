package dev.fogo.dokkantranslate.match

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/**
 * Keeps the card data current without reinstalling the app.
 *
 * What ships in the APK is only a floor — whatever was true when it was
 * built — so a card released afterwards could never be matched. CI
 * refreshes the committed data weekly; this pulls it down into internal
 * storage, which [CardIndex] and [KitStore] prefer over the assets.
 *
 * Two files, always moved together: the index holds byte offsets into the
 * kit blob, so a mismatched pair would read garbage. They are downloaded
 * to temporary names, checked, and only then swapped in.
 */
object IndexUpdater {

    private const val RAW =
        "https://raw.githubusercontent.com/okfaygo/dokkan-translation/main/" +
            "android/app/src/main/assets/"
    private const val INDEX = "index.json"
    private const val KITS = "kits.json.gz"
    private const val ETAG = "index.etag"
    private const val MISSIONS = "missions.json"
    private const val MISSIONS_ETAG = "missions.etag"

    /** Shape this build understands; a newer pair is rejected rather than
     *  misread. Bumped alongside DATA_VERSION in build_index.py. */
    private const val SUPPORTED_VERSION = 2
    private const val MIN_INDEX_BYTES = 512 * 1024
    private const val MIN_KITS_BYTES = 128 * 1024
    /** missions.json has its own DATA_VERSION (build_missions.py) */
    private const val SUPPORTED_MISSIONS_VERSION = 1
    private const val MIN_MISSIONS_BYTES = 512 * 1024

    fun downloadedIndex(context: Context): File? =
        File(context.filesDir, INDEX).takeIf { it.length() >= MIN_INDEX_BYTES }

    fun downloadedKits(context: Context): File? =
        File(context.filesDir, KITS).takeIf { it.length() >= MIN_KITS_BYTES }

    fun downloadedMissions(context: Context): File? =
        File(context.filesDir, MISSIONS).takeIf { it.length() >= MIN_MISSIONS_BYTES }

    /**
     * The mission table on its own: unlike the card pair it holds no
     * offsets into anything, so it is fetched and swapped independently.
     * @return true when a newer table was installed.
     */
    fun refreshMissions(context: Context): Boolean {
        val etagFile = File(context.filesDir, MISSIONS_ETAG)
        val etag = if (downloadedMissions(context) != null && etagFile.exists()) {
            etagFile.readText().trim()
        } else null
        val temp = File(context.filesDir, "$MISSIONS.part")
        try {
            val (ok, newEtag) = download(RAW + MISSIONS, temp, etag) ?: return false
            if (!ok || temp.length() < MIN_MISSIONS_BYTES) return false
            val version = runCatching {
                JSONObject(temp.readText(Charsets.UTF_8))
                    .optJSONObject("__meta__")?.optInt("data_version", 0) ?: 0
            }.getOrDefault(0)
            if (version != SUPPORTED_MISSIONS_VERSION) return false
            if (!replace(temp, File(context.filesDir, MISSIONS))) return false
            newEtag?.let { etagFile.writeText(it) }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            temp.delete()
        }
    }

    /** @return true when a newer pair was installed. */
    fun refresh(context: Context): Boolean {
        val etagFile = File(context.filesDir, ETAG)
        val haveBoth = downloadedIndex(context) != null && downloadedKits(context) != null
        val etag = if (haveBoth && etagFile.exists()) etagFile.readText().trim() else null

        val indexTemp = File(context.filesDir, "$INDEX.part")
        val kitsTemp = File(context.filesDir, "$KITS.part")
        try {
            val (indexOk, newEtag) = download(RAW + INDEX, indexTemp, etag)
                ?: return false                      // 304 or failure
            if (!indexOk || indexTemp.length() < MIN_INDEX_BYTES) return false

            // Reject a pair this build cannot read before touching anything.
            val version = runCatching {
                JSONObject(indexTemp.readText(Charsets.UTF_8))
                    .optJSONObject("__meta__")?.optInt("data_version", 1) ?: 1
            }.getOrDefault(0)
            if (version != SUPPORTED_VERSION) return false

            val (kitsOk, _) = download(RAW + KITS, kitsTemp, null) ?: return false
            if (!kitsOk || kitsTemp.length() < MIN_KITS_BYTES) return false
            if (!gzipReadable(kitsTemp)) return false

            // Swap only once both are on disk and sane.
            if (!replace(indexTemp, File(context.filesDir, INDEX))) return false
            if (!replace(kitsTemp, File(context.filesDir, KITS))) return false
            newEtag?.let { etagFile.writeText(it) }
            KitStore.invalidate()
            return true
        } catch (e: Exception) {
            return false
        } finally {
            indexTemp.delete()
            kitsTemp.delete()
        }
    }

    /** @return null on 304 or error; else (ok, etag). */
    private fun download(url: String, target: File, etag: String?): Pair<Boolean, String?>? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Accept-Encoding", "gzip")
            if (etag != null) setRequestProperty("If-None-Match", etag)
        }
        try {
            when (conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> return null
                HttpURLConnection.HTTP_OK -> Unit
                else -> return null
            }
            // NB: only the transfer encoding is unwrapped here. kits.json.gz
            // is gzip *content* and must stay compressed on disk.
            val transferGzip = conn.contentEncoding?.equals("gzip", true) == true
            conn.inputStream.use { raw ->
                val body = if (transferGzip) GZIPInputStream(raw) else raw
                target.outputStream().use { out -> body.copyTo(out) }
            }
            return true to conn.getHeaderField("ETag")
        } catch (e: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun gzipReadable(file: File): Boolean = runCatching {
        GZIPInputStream(file.inputStream()).use { it.read() >= 0 }
    }.getOrDefault(false)

    private fun replace(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        return runCatching {
            from.copyTo(to, overwrite = true)
            true
        }.getOrDefault(false)
    }
}
