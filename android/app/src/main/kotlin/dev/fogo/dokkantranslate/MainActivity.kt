package dev.fogo.dokkantranslate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.fogo.dokkantranslate.bubble.BubbleService
import dev.fogo.dokkantranslate.identify.CardIdentifier
import dev.fogo.dokkantranslate.identify.MatchDebug
import dev.fogo.dokkantranslate.ui.AppScreen
import dev.fogo.dokkantranslate.ui.UiState
import dev.fogo.dokkantranslate.ui.toUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var state by mutableStateOf<UiState>(UiState.Idle)
    private var bubbleRunning by mutableStateOf(false)

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let(::identify)
        }

    /** Screen-capture consent; the token goes straight to the service. */
    private val requestProjection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                BubbleService.start(this, result.resultCode, data)
                bubbleRunning = true
                moveTaskToBack(true) // get out of the way so the game is visible
            }
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // notification is only the FGS banner; proceed either way
            askForProjection()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppScreen(
                state = state,
                onPickImage = { pickImage.launch("image/*") },
                onSelectCard = ::lookUp,
                bubbleRunning = bubbleRunning,
                onStartBubble = ::startBubble,
                onStopBubble = {
                    BubbleService.stop(this)
                    bubbleRunning = false
                },
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

    // ---- bubble start-up: overlay permission -> notifications -> consent --

    private fun startBubble() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
            state = UiState.Failed(
                "Allow \"Display over other apps\" for Dokkan Translate, " +
                    "then come back and start the bubble again."
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        askForProjection()
    }

    private fun askForProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        requestProjection.launch(manager.createScreenCaptureIntent())
    }

    // ---- share-sheet / gallery flow --------------------------------------

    private fun identify(uri: Uri) {
        lifecycleScope.launch {
            try {
                state = UiState.Working("Reading image…")
                val bitmap = withContext(Dispatchers.IO) { decode(uri) }
                state = CardIdentifier.identify(this@MainActivity, bitmap) { step ->
                    state = UiState.Working(step)
                }.toUiState()
            } catch (e: Exception) {
                state = UiState.Failed(e.message ?: e.toString())
            }
        }
    }

    private fun lookUp(cardId: String) {
        val current = state as? UiState.Result
        lifecycleScope.launch {
            try {
                state = CardIdentifier.lookUp(
                    this@MainActivity,
                    cardId,
                    current?.alternatives ?: emptyList(),
                    current?.debug ?: MatchDebug(),
                ) { step -> state = UiState.Working(step) }.toUiState()
            } catch (e: Exception) {
                state = UiState.Failed(e.message ?: e.toString())
            }
        }
    }

    private fun decode(uri: Uri): Bitmap =
        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalArgumentException("Couldn't decode the shared image")
}
