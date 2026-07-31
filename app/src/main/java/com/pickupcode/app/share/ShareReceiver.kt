package com.pickupcode.app.share

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.pickupcode.app.data.AppDatabase
import com.pickupcode.app.data.CodeHistory
import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.geocoder.GeocoderVerifier
import com.pickupcode.app.notification.CodeNotificationManager
import com.pickupcode.app.ocr.OCREngine
import com.pickupcode.app.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ShareReceiver {

    private const val TAG = "ShareReceiver"

    // Handle share/drag-drop intents from other apps.
    // Reads settings asynchronously -- does not block the caller.
    //
    // Supports:
    // - ACTION_PROCESS_TEXT: selected text (text selection menu, vivo Atomic Island drag)
    // - ACTION_SEND text/plain: direct text extraction
    // - ACTION_SEND image: OCR recognition
    fun handle(context: Context, intent: Intent?, scope: CoroutineScope) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_PROCESS_TEXT) return

        scope.launch {
            val settings = withContext(Dispatchers.IO) {
                AppPreferences.observe(context).first()
            }
            val isShare = action == Intent.ACTION_SEND
            val isProcessText = action == Intent.ACTION_PROCESS_TEXT
            if (isShare && !settings.enableIntentReceive) {
                Log.d(TAG, "Intent receive disabled, skip")
                return@launch
            }
            if (isProcessText && !settings.enableShareDetection) {
                Log.d(TAG, "Share detection disabled, skip")
                return@launch
            }
            Log.d(TAG, "Received: action=$action, type=${intent.type}")
            dispatch(context, intent, isProcessText)
        }
    }

    private suspend fun dispatch(context: Context, intent: Intent, isProcessText: Boolean) {
        if (isProcessText) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            if (!text.isNullOrBlank()) processText(context, text, "TextSelection")
        } else when {
            intent.type?.startsWith("text/") == true -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) processText(context, text, "SharedText")
            }
            intent.type?.startsWith("image/") == true -> {
                val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    ?: intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) processImage(context, uri, "SharedImage")
            }
        }
    }

    private suspend fun processText(context: Context, text: String, sourceLabel: String) {
        val lines = text.lines().map { line ->
            OCREngine.TextLine(text = line.trim(), boundingBox = null, confidence = 1.0f)
        }.filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return
        val allText = lines.joinToString(" ") { it.text }
        val address = CodeExtractor.extractAddress(lines, allText)
        extractAndNotify(context, lines, "$sourceLabel | ${lines.joinToString(" ") { it.text }.take(200)}", "", address)
    }

    private suspend fun processImage(context: Context, uri: Uri, sourceLabel: String) {
        val bitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { s -> BitmapFactory.decodeStream(s) }
            } catch (e: Exception) {
                Log.e(TAG, "Read image failed: ${e.message}")
                null
            }
        } ?: return

        // Save shared image as screenshot for detail page
        val screenshotPath = withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "shared_images")
                dir.mkdirs()
                val file = File(dir, "share_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                }
                file.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Save screenshot failed: ${e.message}")
                ""
            }
        }

        val lines = withContext(Dispatchers.Default) {
            try {
                OCREngine.recognize(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "OCR failed: ${e.message}")
                emptyList()
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        if (lines.isEmpty()) return
        val allText = lines.joinToString(" ") { it.text }
        val address = CodeExtractor.extractAddress(lines, allText)
        val snippet = "$sourceLabel | ${lines.joinToString(" ") { it.text }.take(200)}"
        extractAndNotify(context, lines, snippet, screenshotPath, address)
    }

    private suspend fun extractAndNotify(
        context: Context,
        lines: List<OCREngine.TextLine>,
        rawSnippet: String,
        screenshotPath: String = "",
        address: String = ""
    ) {
        val results = withContext(Dispatchers.Default) { CodeExtractor.extract(lines, context = context) }
        if (results.isEmpty()) {
            Log.d(TAG, "No pickup code found")
            return
        }
        val db = AppDatabase.getInstance(context)
        val settings = withContext(Dispatchers.IO) { AppPreferences.observe(context).first() }

        for (result in results) {
            // Check for existing same code+type (duplicate)
            val existing = db.codeHistoryDao().findByCodeAndType(result.code, result.type.name)
            val isDuplicate = existing != null

            val history = CodeHistory(
                code = result.code,
                type = result.type.name,
                source = result.source,
                rawTextSnippet = rawSnippet,
                pickupAddress = address,
                screenshotPath = screenshotPath
            )
            val id = db.codeHistoryDao().insert(history)

            // Notify user about duplicate
            if (isDuplicate && existing != null) {
                val dupCount = db.codeHistoryDao().countDuplicateGroups()
                CodeNotificationManager.showDuplicate(
                    context, result.code, result.type, result.source, id, dupCount
                )
            } else {
                CodeNotificationManager.show(context, result.code, result.type, result.source, id)
            }
            Log.d(TAG, "Recognized: ${result.code} (${result.type.name}) from ${result.source}${if (isDuplicate) " [DUPLICATE]" else ""}")

            // Async address geocoding verification
            if (address.isNotBlank() && settings.enableMapVerify) {
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        val geoResult = GeocoderVerifier.verify(
                            context, address,
                            amapApiKey = settings.amapApiKey.ifBlank { null }
                        )
                        if (geoResult.verified) {
                            db.codeHistoryDao().update(history.copy(
                                geoVerified = true,
                                geoConfidence = geoResult.confidence,
                                geoFormattedAddress = geoResult.formattedAddress ?: ""
                            ))
                            Log.d(TAG, "Geo verify OK: $address → ${geoResult.formattedAddress} (${geoResult.confidence})")
                        } else {
                            Log.d(TAG, "Geo verify failed for: $address")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Geo verify error: ${e.message}")
                    }
                }
            }
        }
    }
}