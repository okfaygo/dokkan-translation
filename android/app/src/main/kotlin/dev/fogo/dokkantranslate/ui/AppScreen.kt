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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.fogo.dokkantranslate.UiState
import dev.fogo.dokkantranslate.match.CardRecord
import dev.fogo.dokkantranslate.match.Matcher

@Composable
fun AppScreen(
    state: UiState,
    onPickImage: () -> Unit,
    onSelectAlternative: (Matcher.Candidate) -> Unit,
    onToggleEza: (CardRecord, Boolean) -> Unit,
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
                    is UiState.Failed -> Failed(state.message, onPickImage)
                    is UiState.Result ->
                        KitView(state, onSelectAlternative, onToggleEza, onPickImage)
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
private fun Failed(message: String, onPickImage: () -> Unit) {
    Text("Couldn't identify the card", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text(message, color = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(16.dp))
    Button(onClick = onPickImage) { Text("Try another screenshot") }
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
    onSelectAlternative: (Matcher.Candidate) -> Unit,
    onToggleEza: (CardRecord, Boolean) -> Unit,
    onPickImage: () -> Unit,
) {
    val kit = result.kit

    Text(
        "[${kit.rarity} ${kit.element}] ${kit.title}",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(kit.name, style = MaterialTheme.typography.headlineSmall)

    if (result.record.hasPreEza) {
        Spacer(Modifier.height(8.dp))
        val showingPre = kit.isPreEza
        Text(
            if (showingPre) "Showing the pre-EZA kit" else "Showing the current (EZA) kit",
            style = MaterialTheme.typography.labelMedium,
        )
        OutlinedButton(onClick = { onToggleEza(result.record, !showingPre) }) {
            Text(if (showingPre) "Show EZA kit" else "Show pre-EZA kit")
        }
    }

    SectionHeader("Leader Skill")
    Text(kit.leader)

    SectionHeader("Passive" + if (kit.passiveName.isNotEmpty()) " — ${kit.passiveName}" else "")
    for ((isHeader, row) in kit.passiveRows) {
        if (isHeader) {
            Spacer(Modifier.height(6.dp))
            Text(row, fontWeight = FontWeight.Bold)
        } else {
            Text("•  $row", modifier = Modifier.padding(start = 8.dp))
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

    if (kit.links.isNotEmpty()) {
        SectionHeader("Links")
        Text(kit.links.joinToString(", "))
    }
    if (kit.categories.isNotEmpty()) {
        SectionHeader("Categories")
        Text(kit.categories.joinToString(", "))
    }

    if (result.alternatives.isNotEmpty()) {
        SectionHeader("Not the right card?")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (alt in result.alternatives) {
                OutlinedButton(
                    onClick = { onSelectAlternative(alt) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(alt.record.displayName)
                }
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    OutlinedButton(onClick = onPickImage) { Text("Identify another screenshot") }
}
