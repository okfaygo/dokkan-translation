package dev.fogo.dokkantranslate.match

/**
 * Port of prototype/match.py: every OCR line votes its best fuzzy score
 * (>= threshold) into each card it resembles; highest total wins.
 *
 * `ratio` is rapidfuzz's fuzz.ratio: normalized indel similarity,
 * (len(a)+len(b) - indel_distance) / (len(a)+len(b)) * 100, where
 * indel_distance = len(a)+len(b) - 2*LCS(a, b).
 */
object Matcher {

    data class Candidate(val record: CardRecord, val score: Double)

    fun rank(
        ocrLines: List<String>,
        index: List<CardRecord>,
        threshold: Double = 70.0,
    ): List<Candidate> {
        val scores = HashMap<CardRecord, Double>()
        for (raw in ocrLines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            for (rec in index) {
                var best = 0.0
                for (key in rec.keys) {
                    // cheap upper bound from the length difference alone
                    val upper = 200.0 * minOf(line.length, key.length) /
                        (line.length + key.length)
                    if (upper < threshold || upper <= best) continue
                    val r = ratio(line, key)
                    if (r > best) best = r
                }
                if (best >= threshold) {
                    scores[rec] = (scores[rec] ?: 0.0) + best
                }
            }
        }
        return scores.entries
            .map { Candidate(it.key, it.value) }
            .sortedByDescending { it.score }
    }

    fun ratio(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val total = a.length + b.length
        return 200.0 * lcsLength(a, b) / total
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
