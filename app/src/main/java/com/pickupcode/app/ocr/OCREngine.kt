package com.pickupcode.app.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await

object OCREngine {

    @Volatile
    private var recognizer: TextRecognizer? = null

    private fun getRecognizer(): TextRecognizer {
        return recognizer ?: synchronized(this) {
            recognizer ?: TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build()
            ).also { recognizer = it }
        }
    }

    data class TextLine(
        val text: String,
        val boundingBox: android.graphics.Rect?,
        val confidence: Float?
    )

    // Unicode dash variants that OCR often produces instead of ASCII "-" (U+002D).
    // Normalizing these ensures CodeExtractor's regex patterns match correctly.
    // U+30FC is the Japanese long-vowel mark (ー), which the Chinese OCR model
    // frequently outputs for a hyphen in courier codes like D-06003.
    private val UNICODE_DASHES = Regex("[\u2010-\u2015\u2212\uFE58\uFE63\uFF0D\u30FC]")

    suspend fun recognize(bitmap: Bitmap): List<TextLine> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = getRecognizer().process(image)
        return result.await().textBlocks.flatMap { block ->
            block.lines.map { line ->
                TextLine(
                    text = line.text.trim().replace(UNICODE_DASHES, "-"),
                    boundingBox = line.boundingBox,
                    confidence = line.confidence
                )
            }
        }
    }

    fun close() {
        recognizer?.close()
        recognizer = null
    }
}
