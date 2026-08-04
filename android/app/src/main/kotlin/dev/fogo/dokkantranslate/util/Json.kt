package dev.fogo.dokkantranslate.util

import org.json.JSONObject

/**
 * `optString` is a trap: for a JSON null it returns the literal string
 * "null", not "" and not null — so the usual `.ifEmpty { null }` guard
 * sails straight past it and "null" ends up rendered on screen (or, worse,
 * indexed as a match key). Always go through this.
 */
fun JSONObject.stringOrNull(key: String): String? {
    if (isNull(key)) return null
    return optString(key).ifEmpty { null }
}

fun JSONObject.stringOr(key: String, fallback: String = ""): String =
    stringOrNull(key) ?: fallback
