package dev.fogo.dokkantranslate

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.fogo.dokkantranslate.api.DokkanInfo
import dev.fogo.dokkantranslate.api.Kit
import dev.fogo.dokkantranslate.match.CardIndex
import dev.fogo.dokkantranslate.match.CardRecord
import dev.fogo.dokkantranslate.match.Matcher
import dev.fogo.dokkantranslate.ocr.OcrEngine
import dev.fogo.dokkantranslate.ui.AppScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the matcher saw, kept for the debug panel. */
data class MatchDebug(
    val ocrLines: List<String> = emptyList(),
    val topCandidates: List<Pair<String, Double>> = emptyList(),
    val tiedCount: Int = 0,
    val typeHint: String? = null,
    val rarityHint: String? = null,
)

sealed interface UiState {
    data object Idle : UiState
    data class Working(val step: String) : UiState
    data class Failed(val message: String, val debug: MatchDebug = MatchDebug()) : UiState
    data class Result(
        val kit: Kit,
        val alternatives: List<Matcher.Candidate>,
        /** several cards matched equally — the shown kit is a guess */
        val ambiguous: Boolean = false,
        val debug: MatchDebug = MatchDebug(),
    ) : UiState
}

class MainActivity : ComponentActivity() {

    private var state by mutableStateOf<UiState>(UiState.Idle)

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let(::identify)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppScreen(
                state = state,
                onPickImage = { pickImage.launch("image/*") },
                onSelectCard = ::lookUp,
            )
        }
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        identify(uri)
    }

    private fun identify(uri: Uri) {
        lifecycleScope.launch {
            try {
                state = UiState.Working("Reading image…")
                val bitmap = withContext(Dispatchers.IO) { decode(uri) }

                state = UiState.Working("Recognizing Japanese text…")
                val lines = OcrEngine.recognizeJapaneseLines(bitmap)
                if (lines.isEmpty()) {
                    state = UiState.Failed(
                        "No Japanese text found in the image. " +
                            "Share a screenshot from JP Dokkan — the passive-detail " +
                            "popup works best, the card page also works.",
                        MatchDebug(),
                    )
                    return@launch
                }

                state = UiState.Working("Matching against the card index…")
                val ranked = withContext(Dispatchers.Default) {
                    Matcher.rank(lines, CardIndex.load(this@MainActivity))
                }
                val (elHint, rarHint) = Matcher.extractHints(lines)
                val debug = MatchDebug(
                    ocrLines = lines,
                    topCandidates = ranked.take(6)
                        .map { it.record.displayLabel to it.score },
                    tiedCount = Matcher.tiedCount(ranked),
                    typeHint = elHint?.let { CardRecord.elementName(it) },
                    rarityHint = rarHint?.let { RARITY_NAMES[it] },
                )
                if (ranked.isEmpty()) {
                    state = UiState.Failed(
                        "Couldn't match any card from ${lines.size} recognized lines.",
                        debug,
                    )
                    return@launch
                }

                // When many candidates tie the screenshot lacked card-specific
                // text; show a longer list so the right card stays reachable.
                val ambiguous = debug.tiedCount >= Matcher.AMBIGUOUS_AT
                val altCount = if (ambiguous) 8 else 3
                fetchAndShow(
                    ranked.first().record.id,
                    ranked.first().record.altKeys.isNotEmpty(),
                    ranked.first().record.ezaStep,
                    ranked.drop(1).take(altCount),
                    ambiguous,
                    debug,
                )
            } catch (e: Exception) {
                state = UiState.Failed(e.message ?: e.toString())
            }
        }
    }

    /** Look up a card id (alternatives list, transformations). */
    private fun lookUp(cardId: String) {
        val record = CardIndex.load(this).firstOrNull { it.id == cardId }
        val altView = record?.altKeys?.isNotEmpty() == true
        val prev = state as? UiState.Result
        lifecycleScope.launch {
            try {
                fetchAndShow(
                    cardId,
                    altView,
                    record?.ezaStep ?: 0,
                    prev?.alternatives ?: emptyList(),
                    // the user picked this one, so it's no longer a guess
                    ambiguous = false,
                    debug = prev?.debug ?: MatchDebug(),
                )
            } catch (e: Exception) {
                state = UiState.Failed(e.message ?: e.toString())
            }
        }
    }

    private suspend fun fetchAndShow(
        cardId: String,
        altView: Boolean,
        ezaStep: Int,
        alternatives: List<Matcher.Candidate>,
        ambiguous: Boolean,
        debug: MatchDebug,
    ) {
        state = UiState.Working("Fetching English kit…")
        val kit = withContext(Dispatchers.IO) {
            DokkanInfo.fetch(this@MainActivity, cardId, altView, ezaStep)
        }
        state = UiState.Result(kit, alternatives, ambiguous, debug)
    }

    private fun decode(uri: Uri): Bitmap =
        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalArgumentException("Couldn't decode the shared image")

    companion object {
        private val RARITY_NAMES = mapOf(3 to "SSR", 4 to "UR", 5 to "LR")
    }
}
