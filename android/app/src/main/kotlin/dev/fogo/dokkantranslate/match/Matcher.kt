package dev.fogo.dokkantranslate.match

/**
 * Port of prototype/match.py: every OCR line votes its best fuzzy score
 * (>= threshold) into each card it resembles; highest total wins.
 *
 * Votes are weighted by line length so a full passive sentence outvotes
 * short fragments (category chips, UI labels, OCR shrapnel). Pre- and
 * post-EZA key groups are scored separately, so the winner also tells us
 * which EZA state the screenshot shows.
 *
 * `ratio` is rapidfuzz's fuzz.ratio: normalized indel similarity
 * 2*LCS(a,b)/(len(a)+len(b))*100 — verified identical to float precision.
 */
object Matcher {

    data class Candidate(
        val record: CardRecord,
        val score: Double,
        /** true when the pre-EZA kit lines matched better than the current kit */
        val matchedPreEza: Boolean,
    )

    fun rank(
        ocrLines: List<String>,
        index: List<CardRecord>,
        threshold: Double = 70.0,
    ): List<Candidate> {
        val post = HashMap<CardRecord, Double>()
        val pre = HashMap<CardRecord, Double>()
        for (raw in ocrLines) {
            val line = raw.trim()
            if (line.length < 4) continue
            val weight = minOf(line.length, 24) / 24.0
            for (rec in index) {
                val bestPost = bestRatio(line, rec.postKeys, threshold)
                if (bestPost >= threshold) {
                    post[rec] = (post[rec] ?: 0.0) + bestPost * weight
                }
                if (rec.preKeys.isNotEmpty()) {
                    val bestPre = bestRatio(line, rec.preKeys, threshold)
                    if (bestPre >= threshold) {
                        pre[rec] = (pre[rec] ?: 0.0) + bestPre * weight
                    }
                }
            }
        }
        val all = HashSet<CardRecord>(post.keys).apply { addAll(pre.keys) }
        return all.map { rec ->
            val p = post[rec] ?: 0.0
            val q = pre[rec] ?: 0.0
            Candidate(rec, maxOf(p, q), matchedPreEza = q > p)
        }.sortedByDescending { it.score }
    }

    private fun bestRatio(line: String, keys: List<String>, threshold: Double): Double {
        var best = 0.0
        for (key in keys) {
            // cheap upper bound from the length difference alone
            val upper = 200.0 * minOf(line.length, key.length) /
                (line.length + key.length)
            if (upper < threshold || upper <= best) continue
            val r = ratio(line, key)
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
