package dev.fogo.dokkantranslate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The bubble's result panel: same kit rendering as the main screen, wrapped
 * in a dismissible sheet that sits over the game.
 */
@Composable
fun BubblePanel(
    state: UiState,
    onSelectCard: (String) -> Unit,
    onClose: () -> Unit,
    onResume: () -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Dokkan Translate",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (state) {
                        is UiState.Idle -> Text("Tap the bubble over a card.")
                        is UiState.Working -> Working(state.step)
                        is UiState.Failed -> {
                            Text(
                                state.message,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = onResume) {
                                Text("Resume screen capture")
                            }
                            DebugPanel(state.debug)
                        }
                        is UiState.Result -> KitView(
                            result = state,
                            onSelectCard = onSelectCard,
                            onPickImage = onClose,
                            bottomActionLabel = "Close",
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
