package dev.fogo.dokkantranslate.match

/**
 * Port of prototype/match_missions.py — keep the two in step, the Python
 * side is where bench_missions.py measures changes before they land here.
 *
 * A mission screen shows SEVERAL missions, so the answer is a list:
 *  1. segments = each OCR line, plus each adjacent pair joined (names wrap
 *     onto two lines where the JP text has its \n; "を3回クリア" alone is
 *     useless). A pair is only compared with keys that span lines.
 *  2. fuzzy ratio >= THRESHOLD, and digits must agree — templated missions
 *     differ ONLY in their numbers ("ステージ1を1回クリア" vs "ステージ2…").
 *  3. event vote: 402 missions are literally named "clear the specified
 *     Extreme Z-Battle under special conditions"; the event decides which.
 *  4. greedy cover: the mission explaining the most still-unexplained text,
 *     as ONE contiguous block of lines, wins and claims them; repeat.
 */
object MissionMatcher {

    private const val THRESHOLD = 82.0
    private const val DIGIT_PENALTY = 20.0
    private const val TIE_BAND = 3.0
    private const val MIN_SEGMENT = 5
    private const val MIN_LINES_FOR_MISSIONS = 2
    /** an unreadable line inside a description must not split it in two */
    private const val MAX_GAP = 1
    /** least text a mission must explain to be reported (~8 well-read chars) */
    private const val MIN_VALUE = THRESHOLD * 8

    data class Hit(
        val mission: MissionRecord,
        /** false when the name fits several events' missions and no event
         *  could be told apart: the name is right, the description might
         *  be a twin's */
        val confident: Boolean,
    )

    data class Result(
        val hits: List<Hit>,
        val usedLines: Int,
        /** on the card matcher's scale, so the two readings compare */
        val score: Double,
    )

    /**
     * Missions win only if they explain more of the screen than the best
     * card does. Mission descriptions quote card names and categories
     * ("「地球を守りし英雄」カテゴリの"), so a card page always explains SOME
     * mission text — but its own card explains far more.
     */
    fun isMissionScreen(result: Result, cardScore: Double): Boolean =
        result.usedLines >= MIN_LINES_FOR_MISSIONS && result.score > cardScore

    private class Segment(val first: Int, val last: Int, val text: String)

    /** One segment's match to one mission. */
    private class Evidence(
        val first: Int,
        val last: Int,
        /** ranks claims: ratio x min(len, 40) */
        val value: Double,
        /** ratio x min(len, 24)/24, the card matcher's line weight */
        val scale: Double,
    )

    private class Claim(val value: Double, val lines: Set<Int>, val scale: Double)

    fun match(ocrLines: List<String>, index: MissionIndex): Result {
        val lines = ocrLines.map { it.trim() }
        val segments = segments(lines)

        // per segment: best ratio for each (mission or event) reference
        val hits = ArrayList<Pair<Segment, Map<MissionRef, Double>>>()
        val overlap = IntArray(index.keys.size)
        for (seg in segments) {
            val best = bestRefs(seg, index, overlap)
            if (best.isNotEmpty()) hits.add(seg to best)
        }

        // event vote: single lines only, so a joined pair doesn't count twice
        val votes = HashMap<String, Double>()
        for ((seg, best) in hits) {
            if (seg.first != seg.last) continue
            val perCat = HashMap<String, Double>()
            for ((ref, r) in best) perCat[ref.category] = maxOf(perCat[ref.category] ?: 0.0, r)
            val weight = minOf(seg.text.length, 24) / 24.0
            // a key shared by many events says little about which one
            for ((cat, r) in perCat) votes[cat] = (votes[cat] ?: 0.0) + r * weight / perCat.size
        }
        val topCat = votes.keys.maxWithOrNull(
            compareBy<String> { votes.getValue(it) }
                .thenBy { index.categoryNewest[it] ?: 0L }
                .thenBy { it.toLongOrNull() ?: 0L }
        )

        val evidence = HashMap<MissionRecord, ArrayList<Evidence>>()
        for ((seg, best) in hits) {
            for ((ref, r) in best) {
                val m = ref.mission ?: continue
                evidence.getOrPut(m) { ArrayList() }.add(
                    Evidence(
                        seg.first, seg.last,
                        r * minOf(seg.text.length, 40),
                        r * minOf(seg.text.length, 24) / 24.0,
                    )
                )
            }
        }

        // Greedy cover. A detail popup's lines all point at one mission,
        // while a shared line ("チャレンジイベント") points at thousands, so
        // the real one wins by explaining all of them at once.
        val used = HashSet<Int>()
        val explained = HashSet<Int>()
        val chosen = LinkedHashMap<MissionRecord, Pair<Int, Boolean>>()
        var score = 0.0
        while (true) {
            val claims = HashMap<MissionRecord, Claim>()
            for ((m, ev) in evidence) {
                if (m in chosen) continue
                claim(ev, used)?.let { claims[m] = it }
            }
            if (claims.isEmpty()) break
            val top = claims.values.maxOf { it.value }
            if (top < MIN_VALUE) break

            val near = claims.keys.filter { claims.getValue(it).value >= top * (1 - TIE_BAND / 100) }
            val inCat = near.filter { it.category == topCat }
            val order = compareBy<MissionRecord> { claims.getValue(it).value }
                .thenBy { it.idNumber }
            var pick = (inCat.ifEmpty { near }).maxWith(order)
            val won = claims.getValue(pick)
            used.addAll(won.lines)

            if (inCat.isEmpty() && near.size > 1) {
                // No event to tell twins apart. Identical JP text is the same
                // instruction (Global's wording drifts: "special cond." vs
                // "special conditions"), so show the most common English;
                // different JP texts are different missions — skip.
                if (near.map { it.nameJa }.toSet().size > 1) continue
                val topEn = near.mapNotNull { it.nameEn }
                    .groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }?.key
                if (topEn != null) {
                    pick = near.filter { it.nameEn == topEn }.maxBy { it.idNumber }
                }
            }
            val confident = inCat.isNotEmpty() || near.size == 1
            chosen[pick] = won.lines.min() to confident
            score += won.scale
            explained.addAll(won.lines)
        }

        val ordered = chosen.entries.sortedBy { it.value.first }
            .map { Hit(it.key, it.value.second) }
        return Result(ordered, explained.size, score)
    }

    private fun segments(lines: List<String>): List<Segment> {
        val out = ArrayList<Segment>()
        for (i in lines.indices) {
            val line = lines[i]
            if (line.length >= MIN_SEGMENT) out.add(Segment(i, i, line))
            if (i + 1 < lines.size && lines[i + 1].isNotEmpty()) {
                val joined = line + lines[i + 1]
                if (joined.length >= MIN_SEGMENT) out.add(Segment(i, i + 1, joined))
            }
        }
        return out
    }

    private fun bestRefs(
        seg: Segment,
        index: MissionIndex,
        overlap: IntArray,
    ): Map<MissionRef, Double> {
        // Upper bound on the LCS with every key at once: shared characters,
        // counted with multiplicity. Cheap, and exact as a bound.
        overlap.fill(0)
        for ((ch, n) in MissionIndex.charCounts(seg.text)) {
            val (ks, counts) = index.postings[ch] ?: continue
            for (i in ks.indices) overlap[ks[i]] += minOf(n, counts[i])
        }
        val isPair = seg.first != seg.last
        val segDigits = digits(seg.text)
        val best = HashMap<MissionRef, Double>()
        for (k in index.keys.indices) {
            if (overlap[k] == 0) continue
            // A pair is only evidence for text that really spans two lines:
            // "挑戦する" + "セル(完全体)(GT)の" still scores ~83 against the
            // one-line name — a button label glued onto a name.
            if (isPair && !index.multiline[k]) continue
            val key = index.keys[k]
            val total = seg.text.length + key.length
            if (200.0 * overlap[k] / total < THRESHOLD) continue
            var r = 200.0 * lcsLength(seg.text, key) / total
            if (r < THRESHOLD) continue
            if (digits(key) != segDigits) r -= DIGIT_PENALTY
            if (r < THRESHOLD) continue
            for (ref in index.refs[k]) {
                if (r > (best[ref] ?: 0.0)) best[ref] = r
            }
        }
        return best
    }

    /**
     * The best CONTIGUOUS block of one mission's evidence. One mission's
     * text sits in one place on screen; without this, a mission could take
     * one list entry's first line and another entry's second line and
     * outscore both real entries.
     */
    private fun claim(evidence: List<Evidence>, used: Set<Int>): Claim? {
        val free = evidence
            .filter { e -> (e.first..e.last).none { it in used } }
            .sortedWith(compareBy<Evidence> { it.first }.thenByDescending { it.value })
        var best: Claim? = null
        var block = ArrayList<Evidence>()
        var blockEnd = -1

        fun close() {
            if (block.isEmpty()) return
            val taken = HashSet<Int>()
            var total = 0.0
            var scale = 0.0
            for (e in block.sortedByDescending { it.value }) {
                if ((e.first..e.last).any { it in taken }) continue
                for (i in e.first..e.last) taken.add(i)
                total += e.value
                scale += e.scale
            }
            if (best == null || total > best!!.value) best = Claim(total, taken, scale)
        }

        for (e in free) {
            if (block.isNotEmpty() && e.first <= blockEnd + 1 + MAX_GAP) {
                block.add(e)
                blockEnd = maxOf(blockEnd, e.last)
                continue
            }
            close()
            block = arrayListOf(e)
            blockEnd = e.last
        }
        close()
        return best
    }

    /** Runs of digits, like Python's re.findall(r"\d+") (Unicode-aware). */
    private fun digits(s: String): List<String> {
        val out = ArrayList<String>()
        val run = StringBuilder()
        for (ch in s) {
            if (Character.isDigit(ch)) {
                run.append(ch)
            } else if (run.isNotEmpty()) {
                out.add(run.toString())
                run.setLength(0)
            }
        }
        if (run.isNotEmpty()) out.add(run.toString())
        return out
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
