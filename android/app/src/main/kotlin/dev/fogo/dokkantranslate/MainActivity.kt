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
import dev.fogo.dokkantranslate.match.Matcher
import dev.fogo.dokkantranslate.ocr.OcrEngine
import dev.fogo.dokkantranslate.ui.AppScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiState {
    data object Idle : UiState
    data class Working(val step: String) : UiState
    data class Failed(val message: String) : UiState
    data class Result(
        val kit: Kit,
        val alternatives: List<Matcher.Candidate>,
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
                onSelectCard = { cardId, altView -> lookUp(cardId, altView) },
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
                            "popup works best, the card page also works."
                    )
                    return@launch
                }

                state = UiState.Working("Matching against the card index…")
                val ranked = withContext(Dispatchers.Default) {
                    Matcher.rank(lines, CardIndex.load(this@MainActivity))
                }
                if (ranked.isEmpty()) {
                    state = UiState.Failed(
                        "Couldn't match any card from ${lines.size} recognized lines."
                    )
                    return@launch
                }

                val top = ranked.first()
                fetchAndShow(top.record.id, top.record.altKeys.isNotEmpty(), ranked.drop(1).take(3))
            } catch (e: Exception) {
                state = UiState.Failed(e.message ?: e.toString())
            }
        }
    }

    /** Look up a card id (alternatives list, transformations). */
    private fun lookUp(cardId: String, altView: Boolean) {
        val alternatives = (state as? UiState.Result)?.alternatives ?: emptyList()
        lifecycleScope.launch {
            try {
                fetchAndShow(cardId, altView, alternatives)
            } catch (e: Exception) {
                state = UiState.Failed(e.message ?: e.toString())
            }
        }
    }

    private suspend fun fetchAndShow(
        cardId: String,
        altView: Boolean,
        alternatives: List<Matcher.Candidate>,
    ) {
        state = UiState.Working("Fetching English kit…")
        val kit = withContext(Dispatchers.IO) {
            DokkanInfo.fetch(this@MainActivity, cardId, altView)
        }
        state = UiState.Result(kit = kit, alternatives = alternatives)
    }

    private fun decode(uri: Uri): Bitmap =
        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalArgumentException("Couldn't decode the shared image")
}
