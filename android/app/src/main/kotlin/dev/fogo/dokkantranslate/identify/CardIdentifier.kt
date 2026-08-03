package dev.fogo.dokkantranslate.identify

import android.content.Context
import android.graphics.Bitmap
import dev.fogo.dokkantranslate.api.DokkanInfo
import dev.fogo.dokkantranslate.api.Kit
import dev.fogo.dokkantranslate.match.CardIndex
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
)

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
        val lines = OcrEngine.recognizeJapaneseLines(bitmap)
        if (lines.isEmpty()) {
            return Outcome.Failure(
                "No Japanese text found. Open a card's page or its " +
                    "passive-detail popup — the popup is the most reliable."
            )
        }

        progress.onStep("Matching against the card index…")
        val ranked = withContext(Dispatchers.Default) {
            Matcher.rank(lines, CardIndex.load(context))
        }
        val (elHint, rarHint) = Matcher.extractHints(lines)
        val debug = MatchDebug(
            ocrLines = lines,
            topCandidates = ranked.take(6).map { it.record.displayLabel to it.score },
            tiedCount = Matcher.tiedCount(ranked),
            typeHint = elHint?.let { CardRecord.elementName(it) },
            rarityHint = rarHint?.let { RARITY_NAMES[it] },
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
        return if (record != null) {
            // the user picked this one, so it is no longer a guess
            fetch(context, record, keepAlternatives, false, keepDebug, progress)
        } else {
            fetchById(context, cardId, 0, false, keepAlternatives, keepDebug, progress)
        }
    }

    private suspend fun fetch(
        context: Context,
        record: CardRecord,
        alternatives: List<Matcher.Candidate>,
        ambiguous: Boolean,
        debug: MatchDebug,
        progress: Progress,
    ): Outcome = fetchById(
        context, record.id, record.ezaStep, record.altKeys.isNotEmpty(),
        alternatives, debug, progress, ambiguous,
    )

    private suspend fun fetchById(
        context: Context,
        cardId: String,
        ezaStep: Int,
        altView: Boolean,
        alternatives: List<Matcher.Candidate>,
        debug: MatchDebug,
        progress: Progress,
        ambiguous: Boolean = false,
    ): Outcome {
        progress.onStep("Fetching English kit…")
        return try {
            val kit = withContext(Dispatchers.IO) {
                DokkanInfo.fetch(context, cardId, altView, ezaStep)
            }
            Outcome.Success(kit, alternatives, ambiguous, debug)
        } catch (e: Exception) {
            Outcome.Failure(e.message ?: e.toString(), debug)
        }
    }
}
