package dev.fogo.dokkantranslate.match

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Port of prototype/match.py: every OCR line votes its best fuzzy score
 * (>= threshold) into each card it resembles; highest total wins.
 *
 * Votes are weighted by line length so a full passive sentence outvotes
 * short fragments (category chips, UI labels, OCR shrapnel).
 *
 * Each card's two site views (see CardIndex) are scored separately so
 * either can win. Only one kit is stored per card: the scraper chose the
 * view at build time, so the displayed kit is the one on the screen without
 * anything being selected at runtime.
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

    /**
     * How close two scores must be before the preference ordering (base
     * card, then rarity, then id) overrides the score. Deliberately TIGHT:
     * the cases it exists for — awakening twins, a base card vs its own
     * transformed form — score EXACTLY equal. A loose band silently
     * discards real score differences: at 0.98 a card scoring 161.9 lost
     * to one scoring 160.0 purely because the loser had a higher id.
     */
    private const val TIE_MARGIN = 0.995

    /** Separate, looser band for "several cards are close" — a confidence
     *  signal, not an ordering rule. */
    const val AMBIGUITY_MARGIN = 0.98

    /** Boost-only type/rarity hints from the card page's badges (超知/極力
     *  style type badge, UR/LR emblem). Matching cards get boosted; no
     *  mismatch penalty — benchmarks show a penalty collapses accuracy
     *  when the tiny stylized badges misread, which they will. */
    private const val HINT_BOOST = 1.12
    private val ELEMENT_KANJI =
        mapOf('速' to 0, '技' to 1, '知' to 2, '力' to 3, '体' to 4)
    private val RARITY_MARKERS = mapOf("UR" to 4, "LR" to 5, "SSR" to 3)

    data class Candidate(
        val record: CardRecord,
        val score: Double,
    )

    /**
     * How many candidates are effectively tied with the winner. 1-2 is
     * normal (a card and its awakening twin share text); 3+ means the
     * screenshot didn't contain enough card-specific text to separate a
     * group — e.g. only the character name was readable, and 105 cards
     * are named 超サイヤ人孫悟空. Measured separation: median 2 on good
     * card-page input (1 of 80 reaching 3) vs median 7 when only the name
     * is readable (51 of 60 reaching 3).
     */
    fun tiedCount(ranked: List<Candidate>): Int {
        if (ranked.isEmpty()) return 0
        // against the MAX score, not ranked.first() — the preference
        // ordering can put a slightly lower-scoring card first
        val max = ranked.maxOf { it.score }
        return ranked.count { it.score >= max * AMBIGUITY_MARGIN }
    }

    const val AMBIGUOUS_AT = 3

    /** (element type 0-4 or null, rarity or null) from badge-like lines.
     *  Only unambiguous forms count: 超X/極X for type, exact UR/LR/SSR. */
    fun extractHints(lines: List<String>): Pair<Int?, Int?> {
        var el: Int? = null
        var rar: Int? = null
        for (raw in lines) {
            val s = raw.trim()
            RARITY_MARKERS[s.uppercase()]?.let { rar = it }
            if (s.length == 2 && (s[0] == '超' || s[0] == '極')) {
                ELEMENT_KANJI[s[1]]?.let { el = it }
            }
        }
        return el to rar
    }

    fun rank(
        ocrLines: List<String>,
        index: List<CardRecord>,
        threshold: Double = 70.0,
    ): List<Candidate> =
        finish(scoreRecords(ocrLines, index, threshold), extractHints(ocrLines))

    /**
     * Same result as [rank], computed across all cores.
     *
     * Every record is scored independently, so partitioning by record and
     * merging the partial maps is exact — each record lands in exactly one
     * chunk, and the per-record sum is unchanged. Ordering is therefore
     * identical to [rank], which keeps the Python benchmarks predictive.
     */
    suspend fun rankParallel(
        ocrLines: List<String>,
        index: List<CardRecord>,
        threshold: Double = 70.0,
    ): List<Candidate> = coroutineScope {
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        if (cores == 1 || index.size < 512) {
            return@coroutineScope rank(ocrLines, index, threshold)
        }
        val chunkSize = (index.size + cores - 1) / cores
        val partials = index.chunked(chunkSize).map { chunk ->
            async(Dispatchers.Default) { scoreRecords(ocrLines, chunk, threshold) }
        }.awaitAll()
        val merged = HashMap<CardRecord, Double>()
        for (partial in partials) merged.putAll(partial)
        finish(merged, extractHints(ocrLines))
    }

    private fun scoreRecords(
        ocrLines: List<String>,
        records: List<CardRecord>,
        threshold: Double,
    ): HashMap<CardRecord, Double> {
        val scores = HashMap<CardRecord, Double>()
        for (raw in ocrLines) {
            val line = raw.trim()
            if (line.length < 4) continue
            val weight = minOf(line.length, 24) / 24.0
            for (rec in records) {
                // both views pooled into one key set, matching match.py —
                // scoring them separately made the app disagree with the
                // benchmarks that are supposed to predict it
                var best = bestRatio(line, rec.keys, threshold)
                if (rec.altKeys.isNotEmpty()) {
                    best = maxOf(best, bestRatio(line, rec.altKeys, threshold))
                }
                if (best >= threshold) {
                    scores[rec] = (scores[rec] ?: 0.0) + best * weight
                }
            }
        }
        return scores
    }

    private fun finish(
        scores: Map<CardRecord, Double>,
        hints: Pair<Int?, Int?>,
    ): List<Candidate> {
        val (elHint, rarHint) = hints
        val sorted = scores.map { (rec, raw) ->
            var score = raw
            if (elHint != null && rec.element >= 0 && rec.element % 10 == elHint) {
                score *= HINT_BOOST
            }
            if (rarHint != null && rec.rarity == rarHint) {
                score *= HINT_BOOST
            }
            Candidate(rec, score)
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
