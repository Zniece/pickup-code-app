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

    suspend fun recognize(bitmap: Bitmap): List<TextLine> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = getRecognizer().process(image)
        return result.await().textBlocks.flatMap { block ->
            block.lines.map { line ->
                TextLine(
                    text = line.text.trim(),
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
