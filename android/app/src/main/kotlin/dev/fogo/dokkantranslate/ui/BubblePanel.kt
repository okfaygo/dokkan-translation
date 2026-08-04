package dev.fogo.dokkantranslate.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A card identified earlier in this bubble session. */
data class HistoryEntry(val cardId: String, val label: String)

/**
 * The bubble's result panel: same kit rendering as the main screen, wrapped
 * in a sheet over the game.
 *
 * Collapsing shrinks the window itself (the service resizes it) rather than
 * just hiding content — a full-height transparent window would still eat
 * touches meant for the game.
 */
@Composable
fun BubblePanel(
    state: UiState,
    history: List<HistoryEntry>,
    collapsed: Boolean,
    autoRefresh: Boolean,
    onSelectCard: (String) -> Unit,
    onToggleCollapse: () -> Unit,
    onToggleAutoRefresh: () -> Unit,
    onClose: () -> Unit,
    onResume: () -> Unit,
) {
    val current = (state as? UiState.Result)?.kit
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
                    // collapsed, the header is all that's visible — so show
                    // the card name rather than the app name
                    Text(
                        current?.name?.takeIf { collapsed } ?: "Dokkan Translate",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .weight(1f, fill = false),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onToggleCollapse) {
                            Text(if (collapsed) "Expand" else "Collapse")
                        }
                        TextButton(onClick = onClose) { Text("Close") }
                    }
                }

                if (collapsed) return@Column

                // Experimental and off by default — see BubbleService.
                // Kept because it is occasionally handy when flipping through
                // a box, not because it is a headline feature.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (autoRefresh) "Auto-follow on (experimental)"
                        else "Auto-follow off (experimental)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onToggleAutoRefresh) {
                        Text(
                            if (autoRefresh) "Turn off" else "Turn on",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                if (history.size > 1) {
                    RecentStrip(history, onSelectCard)
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
                            Text(state.message, color = MaterialTheme.colorScheme.error)
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

/**
 * Cards identified earlier this session, newest first. Kits are cached on
 * disk permanently, so revisiting one is instant and costs no capture —
 * this is what removes the repetition that made auto-detection tempting.
 */
@Composable
private fun RecentStrip(
    history: List<HistoryEntry>,
    onSelectCard: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Recent",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        for (entry in history) {
            OutlinedButton(
                onClick = { onSelectCard(entry.cardId) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.padding(end = 6.dp),
            ) {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
