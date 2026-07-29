package dev.fogo.dokkantranslate.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

object OcrEngine {

    private val jpChars = Regex("[\\u3040-\\u30FF\\u4E00-\\u9FFF]")

    /** All recognized lines containing Japanese text, in reading order. */
    suspend fun recognizeJapaneseLines(bitmap: Bitmap): List<String> {
        val recognizer =
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        val lines = ArrayList<String>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val s = line.text.trim()
                if (s.isNotEmpty() && jpChars.containsMatchIn(s)) lines.add(s)
            }
        }
        return lines.distinct()
    }
}
