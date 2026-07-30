package dev.fogo.dokkantranslate.match

/**
 * Port of prototype/match.py: every OCR line votes its best fuzzy score
 * (>= threshold) into each card it resembles; highest total wins.
 *
 * Votes are weighted by line length so a full passive sentence outvotes
 * short fragments (category chips, UI labels, OCR shrapnel).
 *
 * Awakening siblings share (nearly) identical text, so candidates within a
 * small margin of the top score are re-ranked by rarity then id — the later
 * awakening stage wins the tie, matching what a player looking at an
 * awakened card expects.
 *
 * `ratio` is rapidfuzz's fuzz.ratio: normalized indel similarity
 * 2*LCS(a,b)/(len(a)+len(b))*100 — verified identical to float precision.
 */
object Matcher {

    private const val TIE_MARGIN = 0.98

    data class Candidate(val record: CardRecord, val score: Double)

    fun rank(
        ocrLines: List<String>,
        index: List<CardRecord>,
        threshold: Double = 70.0,
    ): List<Candidate> {
        val scores = HashMap<CardRecord, Double>()
        for (raw in ocrLines) {
            val line = raw.trim()
            if (line.length < 4) continue
            val weight = minOf(line.length, 24) / 24.0
            for (rec in index) {
                val best = bestRatio(line, rec.keys, threshold)
                if (best >= threshold) {
                    scores[rec] = (scores[rec] ?: 0.0) + best * weight
                }
            }
        }
        val sorted = scores.entries
            .map { Candidate(it.key, it.value) }
            .sortedByDescending { it.score }
        if (sorted.isEmpty()) return sorted

        // Deterministic tie-break: within the head group, awakened stage first
        val cutoff = sorted.first().score * TIE_MARGIN
        val head = sorted.takeWhile { it.score >= cutoff }
            .sortedWith(
                compareByDescending<Candidate> { it.record.rarity }
                    .thenByDescending { it.record.idNumber }
            )
        return head + sorted.drop(head.size)
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
