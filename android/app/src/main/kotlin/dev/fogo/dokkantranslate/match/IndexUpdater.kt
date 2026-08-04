package dev.fogo.dokkantranslate.match

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Keeps the card index current without reinstalling the app.
 *
 * The index bundled in assets is only the floor: it is whatever was true
 * when the APK was built, so a card released afterwards can never be
 * matched. CI refreshes the committed index weekly; this pulls that copy
 * down and stores it in internal storage, where [CardIndex] prefers it.
 *
 * Conditional on ETag, so the usual outcome is a 304 and no download at
 * all. Failure is always silent — a missed update just means the app keeps
 * using the index it already has, which is exactly the old behaviour.
 */
object IndexUpdater {

    // not named URL — that would shadow java.net.URL and make URL(...) below
    // resolve to this String instead of the constructor
    private const val INDEX_URL =
        "https://raw.githubusercontent.com/okfaygo/dokkan-translation/main/" +
            "android/app/src/main/assets/index.json"
    private const val FILE = "index.json"
    private const val ETAG_FILE = "index.etag"
    private const val MIN_BYTES = 512 * 1024

    /** The downloaded index, or null when only the bundled one exists. */
    fun downloadedIndex(context: Context): File? =
        File(context.filesDir, FILE).takeIf { it.length() >= MIN_BYTES }

    /**
     * @return true when a newer index was installed (callers may want to
     *   drop [CardIndex]'s in-memory copy).
     */
    fun refresh(context: Context): Boolean {
        val target = File(context.filesDir, FILE)
        val etagFile = File(context.filesDir, ETAG_FILE)
        val conn = (URL(INDEX_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Accept-Encoding", "gzip")
            if (target.exists() && etagFile.exists()) {
                setRequestProperty("If-None-Match", etagFile.readText().trim())
            }
        }
        try {
            when (conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> return false
                HttpURLConnection.HTTP_OK -> Unit
                else -> return false
            }
            val gzipped = conn.contentEncoding?.equals("gzip", true) == true
            // Download to a temp file first: a half-written index is worse
            // than a stale one, since it would break matching outright.
            val temp = File(context.filesDir, "$FILE.part")
            conn.inputStream.use { raw ->
                val body = if (gzipped) GZIPInputStream(raw) else raw
                temp.outputStream().use { out -> body.copyTo(out) }
            }
            if (temp.length() < MIN_BYTES || !looksLikeIndex(temp)) {
                temp.delete()
                return false
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            conn.getHeaderField("ETag")?.let { etagFile.writeText(it) }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            conn.disconnect()
        }
    }

    /** Cheap shape check — enough to reject an error page or a truncation. */
    private fun looksLikeIndex(file: File): Boolean = runCatching {
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            val head = CharArray(64)
            val read = reader.read(head)
            read > 0 && String(head, 0, read).trimStart().startsWith("{")
        }
    }.getOrDefault(false)
}
