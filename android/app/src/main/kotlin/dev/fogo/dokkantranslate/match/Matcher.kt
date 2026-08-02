package dev.fogo.dokkantranslate.match

/**
 * Port of prototype/match.py: every OCR line votes its best fuzzy score
 * (>= threshold) into each card it resembles; highest total wins.
 *
 * Votes are weighted by line length so a full passive sentence outvotes
 * short fragments (category chips, UI labels, OCR shrapnel).
 *
 * Each card's two site views (see CardIndex) are scored separately; the
 * winning candidate remembers which view matched, and the app fetches that
 * same view so the displayed kit is the one on the player's screen.
 *
 * Ties: candidates within 2% of the top score are re-ranked to prefer base
 * summonable cards over transformed forms, then higher rarity, then higher
 * id — so an awakened card beats its unawakened sibling, and a base card
 * beats its own transformation.
 *
 * `ratio` is rapidfuzz's fuzz.ratio: normalized indel similarity
 * 2*LCS(a,b)/(len(a)+len(b))*100 — verified identical to float precision.
 */
object Matcher {

    private const val TIE_MARGIN = 0.98

    data class Candidate(
        val record: CardRecord,
        val score: Double,
        /** true when the ?eza=true view matched better than the bare view */
        val matchedAltView: Boolean,
    )

    fun rank(
        ocrLines: List<String>,
        index: List<CardRecord>,
        threshold: Double = 70.0,
    ): List<Candidate> {
        val main = HashMap<CardRecord, Double>()
        val alt = HashMap<CardRecord, Double>()
        for (raw in ocrLines) {
            val line = raw.trim()
            if (line.length < 4) continue
            val weight = minOf(line.length, 24) / 24.0
            for (rec in index) {
                val bestMain = bestRatio(line, rec.keys, threshold)
                if (bestMain >= threshold) {
                    main[rec] = (main[rec] ?: 0.0) + bestMain * weight
                }
                if (rec.altKeys.isNotEmpty()) {
                    val bestAlt = bestRatio(line, rec.altKeys, threshold)
                    if (bestAlt >= threshold) {
                        alt[rec] = (alt[rec] ?: 0.0) + bestAlt * weight
                    }
                }
            }
        }
        val all = HashSet<CardRecord>(main.keys).apply { addAll(alt.keys) }
        val sorted = all.map { rec ->
            val m = main[rec] ?: 0.0
            val a = alt[rec] ?: 0.0
            Candidate(rec, maxOf(m, a), matchedAltView = a > m)
        }.sortedByDescending { it.score }
        if (sorted.isEmpty()) return sorted

        val cutoff = sorted.first().score * TIE_MARGIN
        val head = sorted.takeWhile { it.score >= cutoff }
            .sortedWith(
                compareByDescending<Candidate> { it.record.isBaseCard }
                    .thenByDescending { it.record.rarity }
                    .thenByDescending { it.record.idNumber }
            )
        return head + sorted.drop(head.size)
    }

    /** Card-page leader/SA text arrives as one TRUNCATED line; containment
     *  (how much of the OCR line appears in-order inside the key) rescues
     *  those at a 0.95 discount. The length floor keeps short category
     *  chips from lighting up every kit that mentions them. */
    private const val CONTAIN_MIN_LEN = 14

    private fun bestRatio(line: String, keys: List<String>, threshold: Double): Double {
        var best = 0.0
        val containEligible = line.length >= CONTAIN_MIN_LEN
        for (key in keys) {
            // cheap upper bound from the length difference alone; containment
            // can reach 95 regardless of length, so it bypasses the bound
            val contain = containEligible && key.length > line.length
            val upper = if (contain) 95.0
            else 200.0 * minOf(line.length, key.length) / (line.length + key.length)
            if (upper < threshold || upper <= best) continue
            val lcs = lcsLength(line, key)
            var r = 200.0 * lcs / (line.length + key.length)
            if (contain) {
                val coverage = 100.0 * lcs / line.length
                r = maxOf(r, minOf(coverage, 100.0) * 0.95)
            }
            if (r > best) best = r
        }
        return best
    }

    fun ratio(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 200.0 * lcsLength(a, b) / (a.length + b.length)
    }

    private fun lcsLength(a: String, b: String): Int {
        val (s, t) = if (a.length <= b.length) a to b else b to a
        var prev = IntArray(s.length + 1)
        var curr = IntArray(s.length + 1)
        for (i in 1..t.length) {
            val tc = t[i - 1]
            for (j in 1..s.length) {
                curr[j] = if (s[j - 1] == tc) prev[j - 1] + 1
                else maxOf(prev[j], curr[j - 1])
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[s.length]
    }
}
