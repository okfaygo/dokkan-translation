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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fogo.dokkantranslate.MatchDebug
import dev.fogo.dokkantranslate.UiState

@Composable
fun AppScreen(
    state: UiState,
    onPickImage: () -> Unit,
    onSelectCard: (String) -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (state) {
                    is UiState.Idle -> Idle(onPickImage)
                    is UiState.Working -> Working(state.step)
                    is UiState.Failed -> Failed(state.message, state.debug, onPickImage)
                    is UiState.Result -> KitView(state, onSelectCard, onPickImage)
                }
            }
        }
    }
}

@Composable
private fun Idle(onPickImage: () -> Unit) {
    Text("Dokkan Translate", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    Text(
        "Screenshot a card in JP Dokkan (the passive-detail popup works " +
            "best, the card page also works), then Share it to this app " +
            "to see the English kit.\n\n" +
            "You can also pick a screenshot from your gallery:"
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = onPickImage) { Text("Pick a screenshot") }
}

@Composable
private fun Working(step: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator()
        Text("  $step")
    }
}

@Composable
private fun Failed(message: String, debug: MatchDebug, onPickImage: () -> Unit) {
    Text("Couldn't identify the card", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text(message, color = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(16.dp))
    Button(onClick = onPickImage) { Text("Try another screenshot") }
    DebugPanel(debug)
}

/**
 * What the matcher actually saw. The point of this panel is that a failing
 * screenshot becomes diagnosable without guesswork: if the OCR lines are
 * missing the leader/SA text, the problem is recognition; if they're there
 * but the scores are flat, it's matching.
 */
@Composable
private fun DebugPanel(debug: MatchDebug) {
    if (debug.ocrLines.isEmpty() && debug.topCandidates.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Spacer(Modifier.height(24.dp))
    TextButton(onClick = { open = !open }) {
        Text(if (open) "Hide debug info" else "Show debug info")
    }
    if (!open) return

    Text(
        "OCR read ${debug.ocrLines.size} line(s)" +
            (debug.typeHint?.let { "  ·  type badge: $it" } ?: "  ·  type badge: not read") +
            (debug.rarityHint?.let { "  ·  rarity: $it" } ?: "  ·  rarity: not read"),
        style = MaterialTheme.typography.labelMedium,
    )
    Spacer(Modifier.height(6.dp))
    for (line in debug.ocrLines) {
        Text("· $line", fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
    }
    if (debug.topCandidates.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text(
            "Top candidates (${debug.tiedCount} tied at the top)",
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(4.dp))
        for ((label, score) in debug.topCandidates) {
            Text(
                "%6.1f  %s".format(score, label),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun KitView(
    result: UiState.Result,
    onSelectCard: (String) -> Unit,
    onPickImage: () -> Unit,
) {
    val kit = result.kit

    if (result.ambiguous) {
        Text(
            "This screenshot didn't show enough text unique to one card — " +
                "several matched equally well, so this may be the wrong one. " +
                "Check the list at the bottom, or screenshot the passive-detail " +
                "popup instead.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
    }

    Text(
        "[${kit.rarity} ${kit.element}] ${kit.title}",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(kit.name, style = MaterialTheme.typography.headlineSmall)

    SectionHeader("Leader Skill")
    Text(kit.leader)

    SectionHeader("Passive" + if (kit.passiveName.isNotEmpty()) " — ${kit.passiveName}" else "")
    val passiveIcons = rememberPassiveIcons()
    for ((isHeader, row) in kit.passiveRows) {
        if (isHeader) {
            Spacer(Modifier.height(6.dp))
            Text(
                PassiveIcons.annotate(row),
                inlineContent = passiveIcons,
                fontWeight = FontWeight.Bold,
            )
        } else {
            Text(
                PassiveIcons.annotate("•  $row"),
                inlineContent = passiveIcons,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    if (kit.activeName.isNotEmpty()) {
        SectionHeader("Active Skill — ${kit.activeName}")
        Text(kit.activeDesc)
    }

    if (kit.supers.isNotEmpty()) {
        SectionHeader("Super Attack")
        for ((name, desc) in kit.supers) {
            Text(name, fontWeight = FontWeight.Bold)
            Text(desc)
            Spacer(Modifier.height(4.dp))
        }
    }

    if (kit.transformations.isNotEmpty()) {
        SectionHeader("Transformations")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((id, name) in kit.transformations) {
                OutlinedButton(
                    onClick = { onSelectCard(id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(name)
                }
            }
        }
    }

    if (kit.links.isNotEmpty()) {
        SectionHeader("Links")
        Text(kit.links.joinToString(", "))
    }
    if (kit.categories.isNotEmpty()) {
        SectionHeader("Categories")
        Text(kit.categories.joinToString(", "))
    }

    if (result.alternatives.isNotEmpty()) {
        SectionHeader(
            if (result.ambiguous) "Did you mean one of these?"
            else "Not the right card?"
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (alt in result.alternatives) {
                OutlinedButton(
                    onClick = { onSelectCard(alt.record.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(alt.record.displayLabel)
                }
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    OutlinedButton(onClick = onPickImage) { Text("Identify another screenshot") }
    DebugPanel(result.debug)
}
