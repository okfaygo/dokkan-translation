package dev.fogo.dokkantranslate.ui

import android.content.Context
import android.graphics.Bitmap
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
 * Renders {passiveImg:...} tokens as the in-game icons — the arrows and
 * once/forever badges in passive text, and the status icons (ATK/DEF up
 * and down, stun) used by both passives and super-attack effects.
 *
 * PNGs live in assets/passive_icons/, taken from DokkanInfo's own layout
 * and condition assets, so they match what the site (and game) shows.
 */
object PassiveIcons {

    private val TOKEN = Regex("\\{passiveImg:([a-z_0-9]+)\\}")

    /** Every bundled icon. Sizes are derived from the PNGs, not hardcoded. */
    private val KEYS = listOf(
        "up_g", "down_r", "down_y", "down_g", "once", "forever",
        "stun", "atk_down", "def_down", "astute", "atk_up", "def_up",
    )

    /** Icon height in sp; width follows each PNG's own aspect ratio. */
    private const val ICON_HEIGHT_SP = 15f

    fun token(key: String) = "{passiveImg:$key}"

    /** Replace tokens with inline-content placeholders. */
    fun annotate(text: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        for (m in TOKEN.findAll(text)) {
            append(text.substring(i, m.range.first))
            val key = m.groupValues[1]
            if (key in KEYS) {
                appendInlineContent(key, " ")
            } // unknown token: drop it rather than show a stray placeholder
            i = m.range.last + 1
        }
        append(text.substring(i))
    }

    fun inlineContent(context: Context): Map<String, InlineTextContent> =
        KEYS.mapNotNull { key ->
            val bitmap: Bitmap = runCatching {
                context.assets.open("passive_icons/$key.png")
                    .use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return@mapNotNull null
            if (bitmap.height == 0) return@mapNotNull null

            // Sizing the placeholder to the image's own aspect ratio is what
            // removes the gap beside each icon: ContentScale.Fit letterboxes
            // whenever the box and the image disagree, which reads as a
            // stray space in the middle of a sentence.
            val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
            key to InlineTextContent(
                Placeholder(
                    width = (ICON_HEIGHT_SP * aspect).sp,
                    height = ICON_HEIGHT_SP.sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                )
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
