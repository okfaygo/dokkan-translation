package dev.fogo.dokkantranslate.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.sp

/**
 * Renders {passiveImg:...} tokens as the in-game icons (up/down arrows,
 * once/forever badges, stun/ATK-down/DEF-down/astute status icons).
 * The PNGs live in assets/passive_icons/, pulled from DokkanInfo's layout
 * assets — the same files the site itself substitutes for these tokens.
 */
object PassiveIcons {

    private val TOKEN = Regex("\\{passiveImg:([a-z_]+)\\}")

    /** key -> (placeholder width sp, height sp), matching each PNG's aspect */
    private val SIZES = mapOf(
        "up_g" to (20 to 14), "down_r" to (20 to 14),
        "down_y" to (20 to 14), "down_g" to (20 to 14),
        "once" to (41 to 15), "forever" to (41 to 15),
        "stun" to (24 to 16), "atk_down" to (24 to 16),
        "def_down" to (24 to 16), "astute" to (24 to 16),
    )

    /** Replace tokens with inline-content placeholders. */
    fun annotate(text: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        for (m in TOKEN.findAll(text)) {
            append(text.substring(i, m.range.first))
            val key = m.groupValues[1]
            if (key in SIZES) {
                appendInlineContent(key, " ")
            } // unknown token: drop it, same as the old behavior
            i = m.range.last + 1
        }
        append(text.substring(i))
    }

    fun inlineContent(context: Context): Map<String, InlineTextContent> =
        SIZES.mapNotNull { (key, size) ->
            val bitmap = runCatching {
                context.assets.open("passive_icons/$key.png")
                    .use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return@mapNotNull null
            val (w, h) = size
            key to InlineTextContent(
                Placeholder(w.sp, h.sp, PlaceholderVerticalAlign.TextCenter)
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = key,
                    contentScale = ContentScale.Fit,
                )
            }
        }.toMap()
}

@Composable
fun rememberPassiveIcons(): Map<String, InlineTextContent> {
    val context = LocalContext.current
    return remember { PassiveIcons.inlineContent(context) }
}
