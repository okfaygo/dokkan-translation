package dev.fogo.dokkantranslate.ui

import dev.fogo.dokkantranslate.api.Kit
import dev.fogo.dokkantranslate.identify.MatchDebug
import dev.fogo.dokkantranslate.identify.Outcome
import dev.fogo.dokkantranslate.match.Matcher

/** Shared by the full-screen activity and the floating bubble's panel. */
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

fun Outcome.toUiState(): UiState = when (this) {
    is Outcome.Success -> UiState.Result(kit, alternatives, ambiguous, debug)
    is Outcome.Failure -> UiState.Failed(message, debug)
}
