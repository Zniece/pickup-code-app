package com.pickupcode.app.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

object OCREngine {

    @Volatile
    private var recognizer: TextRecognizer? = null

    // H1/H3: 串行化所有 OCR 调用与关闭，避免 ML Kit "detector busy"/并发 close 竞态
    private val mutex = Mutex()

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
        // H1: 串行化 OCR 调用（lock/unlock 包住挂起体，withLock 的 action 非挂起不能直接 await）
        mutex.lock()
        try {
            return doRecognize(bitmap)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun doRecognize(bitmap: Bitmap): List<TextLine> {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val task = getRecognizer().process(image)
            val result = kotlinx.coroutines.withTimeout(30_000L) { task.await() }
            val out = mutableListOf<TextLine>()
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    out.add(
                        TextLine(
                            text = line.text.trim().replace(UNICODE_DASHES, "-"),
                            boundingBox = line.boundingBox,
                            confidence = line.confidence
                        )
                    )
                }
            }
            return out
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // H-识别引擎#5: 内部兜底，避免 OCR 异常上抛导致整轮失败；无权结果时返回空列表
            android.util.Log.e("OCREngine", "识别失败", e)
            return emptyList()
        }
    }

    fun close() {
        // H3: 与 recognize 用同一把 mutex 同步关闭——避免在途 OCR 未完成就关闭客户端，
        //     也避免 fire-and-forget 作用域泄漏。runBlocking 只阻塞到在途 OCR 完成（有 30s 超时兜底）。
        kotlinx.coroutines.runBlocking {
            mutex.withLock {
                try {
                    recognizer?.close()
                } finally {
                    recognizer = null
                }
            }
        }
    }
}
