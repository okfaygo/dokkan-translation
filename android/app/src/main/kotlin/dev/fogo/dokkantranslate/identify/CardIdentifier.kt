package dev.fogo.dokkantranslate.identify

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import dev.fogo.dokkantranslate.match.CardIndex
import dev.fogo.dokkantranslate.match.Kit
import dev.fogo.dokkantranslate.match.KitStore
import dev.fogo.dokkantranslate.match.CardRecord
import dev.fogo.dokkantranslate.match.Matcher
import dev.fogo.dokkantranslate.ocr.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the matcher saw, kept for the debug panel. */
data class MatchDebug(
    val ocrLines: List<String> = emptyList(),
    val topCandidates: List<Pair<String, Double>> = emptyList(),
    val tiedCount: Int = 0,
    val typeHint: String? = null,
    val rarityHint: String? = null,
    /** per-stage timings, so slowness can be attributed instead of guessed */
    val indexMs: Long = 0,
    val ocrMs: Long = 0,
    val matchMs: Long = 0,
    val fetchMs: Long = 0,
) {
    val timings: String
        get() = "index ${indexMs}ms · OCR ${ocrMs}ms · match ${matchMs}ms · fetch ${fetchMs}ms"
}

sealed interface Outcome {
    data class Success(
        val kit: Kit,
        val alternatives: List<Matcher.Candidate>,
        val ambiguous: Boolean,
        val debug: MatchDebug,
    ) : Outcome

    data class Failure(val message: String, val debug: MatchDebug = MatchDebug()) : Outcome
}

/**
 * Screenshot -> English kit. Shared by the share-sheet flow (MainActivity)
 * and the floating bubble (BubbleService) so both behave identically.
 */
object CardIdentifier {

    private val RARITY_NAMES = mapOf(3 to "SSR", 4 to "UR", 5 to "LR")

    /** Progress steps, so callers can show them however they like. */
    fun interface Progress {
        fun onStep(step: String)
    }

    suspend fun identify(
        context: Context,
        bitmap: Bitmap,
        progress: Progress = Progress {},
    ): Outcome {
        progress.onStep("Recognizing Japanese text…")
        val ocrStart = SystemClock.elapsedRealtime()
        val lines = OcrEngine.recognizeJapaneseLines(bitmap)
        val ocrMs = SystemClock.elapsedRealtime() - ocrStart
        if (lines.isEmpty()) {
            return Outcome.Failure(
                "No Japanese text found. Open a card's page or its " +
                    "passive-detail popup — the popup is the most reliable."
            )
        }

        progress.onStep("Matching against the card index…")
        // usually already warm (BubbleService preloads it), but the first
        // run in a process pays a ~3.5MB JSON parse
        val indexStart = SystemClock.elapsedRealtime()
        val index = withContext(Dispatchers.Default) { CardIndex.load(context) }
        val indexMs = SystemClock.elapsedRealtime() - indexStart

        val matchStart = SystemClock.elapsedRealtime()
        val ranked = Matcher.rankParallel(lines, index)
        val matchMs = SystemClock.elapsedRealtime() - matchStart

        val (elHint, rarHint) = Matcher.extractHints(lines)
        val debug = MatchDebug(
            ocrLines = lines,
            topCandidates = ranked.take(6).map { it.record.displayLabel to it.score },
            tiedCount = Matcher.tiedCount(ranked),
            typeHint = elHint?.let { CardRecord.elementName(it) },
            rarityHint = rarHint?.let { RARITY_NAMES[it] },
            indexMs = indexMs,
            ocrMs = ocrMs,
            matchMs = matchMs,
        )
        if (ranked.isEmpty()) {
            return Outcome.Failure(
                "Couldn't match any card from ${lines.size} recognized lines.",
                debug,
            )
        }

        // Many candidates tied means the screenshot lacked card-specific
        // text; show a longer list so the right card stays reachable.
        val ambiguous = debug.tiedCount >= Matcher.AMBIGUOUS_AT
        val alternatives = ranked.drop(1).take(if (ambiguous) 8 else 3)
        return fetch(context, ranked.first().record, alternatives, ambiguous, debug, progress)
    }

    /** Look up a specific card (alternatives list, transformation buttons). */
    suspend fun lookUp(
        context: Context,
        cardId: String,
        keepAlternatives: List<Matcher.Candidate>,
        keepDebug: MatchDebug,
        progress: Progress = Progress {},
    ): Outcome {
        val record = CardIndex.load(context).firstOrNull { it.id == cardId }
            ?: return Outcome.Failure("Card $cardId isn't in the index.", keepDebug)
        // the user picked this one, so it is no longer a guess
        return fetch(context, record, keepAlternatives, false, keepDebug, progress)
    }

    /**
     * The kit is a seek-and-read out of the packed blob that ships with the
     * index — no network. It used to be a ~211KB page fetch per card, per
     * user, against DokkanInfo.
     */
    private suspend fun fetch(
        context: Context,
        record: CardRecord,
        alternatives: List<Matcher.Candidate>,
        ambiguous: Boolean,
        debug: MatchDebug,
        progress: Progress,
    ): Outcome {
        progress.onStep("Reading kit for ${record.displayLabel}…")
        val start = SystemClock.elapsedRealtime()
        return try {
            val kit = withContext(Dispatchers.IO) { KitStore.kit(context, record) }
            val timed = debug.copy(fetchMs = SystemClock.elapsedRealtime() - start)
            Outcome.Success(kit, alternatives, ambiguous, timed)
        } catch (e: Exception) {
            Outcome.Failure(e.message ?: e.toString(), debug)
        }
    }
}
