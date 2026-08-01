package com.pickupcode.app.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.pickupcode.app.data.AppDatabase
import com.pickupcode.app.data.CodeHistory
import com.pickupcode.app.extractor.AIExtractor
import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.geocoder.GeocoderVerifier
import com.pickupcode.app.kuaidi100.Kuaidi100Verifier
import com.pickupcode.app.notification.CodeNotificationManager
import com.pickupcode.app.ocr.OCREngine
import com.pickupcode.app.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
            dispatch(context, intent, isProcessText, scope)
        }
    }

    private suspend fun dispatch(context: Context, intent: Intent, isProcessText: Boolean, scope: CoroutineScope) {
        if (isProcessText) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            if (!text.isNullOrBlank()) processText(context, text, "TextSelection", scope)
        } else when {
            intent.type?.startsWith("text/") == true -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) processText(context, text, "SharedText", scope)
            }
            intent.type?.startsWith("image/") == true -> {
                val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    ?: intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) processImage(context, uri, "SharedImage", scope)
            }
        }
    }

    private suspend fun processText(context: Context, text: String, sourceLabel: String, scope: CoroutineScope) {
        val lines = text.lines().map { line ->
            OCREngine.TextLine(text = line.trim(), boundingBox = null, confidence = 1.0f)
        }.filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return
        val allText = lines.joinToString(" ") { it.text }
        val address = CodeExtractor.extractAddress(lines, allText)
        extractAndNotify(context, lines, "$sourceLabel | ${lines.joinToString(" ") { it.text }}", "", address, scope)
    }

    private suspend fun processImage(context: Context, uri: Uri, sourceLabel: String, scope: CoroutineScope) {
        val bitmap = withContext(Dispatchers.IO) {
            try {
                decodeSampledBitmap(context, uri)
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
        val snippet = "$sourceLabel | ${lines.joinToString(" ") { it.text }}"
        extractAndNotify(context, lines, snippet, screenshotPath, address, scope)
    }

    private suspend fun extractAndNotify(
        context: Context,
        lines: List<OCREngine.TextLine>,
        rawSnippet: String,
        screenshotPath: String = "",
        address: String = "",
        scope: CoroutineScope
    ) {
        val allText = lines.joinToString(" ") { it.text }
        val db = AppDatabase.getInstance(context)
        val settings = withContext(Dispatchers.IO) { AppPreferences.observe(context).first() }

        // 正则主路径先行（问题3：分享路径接入 AI，但不阻塞）
        val regexResults = withContext(Dispatchers.Default) { CodeExtractor.extract(lines, context = context) }
        val allResults = mutableListOf<CodeExtractor.ExtractedCode>()
        for (re in regexResults) {
            if (re.confidence >= settings.confidenceThreshold && !isTypeDisabled(re.type, settings)) {
                allResults.add(re)
            }
        }

        // AI 异步并行合并（同码同 type 去重；格式已由 isValidPickupCode 把关）
        if (settings.enableAI && settings.apiKey.isNotBlank()) {
            val aiDeferred = scope.async(Dispatchers.IO) {
                AIExtractor.extract(allText, settings.apiKey, settings.apiBaseUrl, settings.apiModel)
            }
            try {
                val aiRes = aiDeferred.await()
                if (aiRes.error != null) Log.w(TAG, "AI 识别失败: ${aiRes.error}")
                for (ai in aiRes.results) {
                    if (isTypeDisabled(ai.type, settings)) continue
                    if (allResults.any { it.code == ai.code && it.type == ai.type }) continue // 同码同type去重
                    // 构造与正则同结构的 ExtractedCode，source 用 AI 识别结果
                    allResults.add(CodeExtractor.ExtractedCode(ai.code, ai.type, ai.source, 1.0f))
                }
            } catch (e: Exception) {
                Log.w(TAG, "AI 结果合并异常: ${e.message}")
            }
        }

        if (allResults.isEmpty()) {
            Log.d(TAG, "No pickup code found")
            return
        }

        for (result in allResults) {
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
                scope.launch(Dispatchers.IO) {
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
                            Log.d(TAG, "Geo verify OK: $address -> ${geoResult.formattedAddress} (${geoResult.confidence})")
                        } else {
                            Log.d(TAG, "Geo verify failed for: $address")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Geo verify error: ${e.message}")
                    }
                }
            }
        }

        // 快递100 反向验证：识别到运单号时反查取件码/地址作为标准答案（fire-and-forget，与无障碍路径一致）
        if (settings.enableKuaidi100 && settings.kuaidi100Key.isNotBlank()) {
            val trackingNum = CodeExtractor.findOrderNumber(allText)
            if (trackingNum != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val res = Kuaidi100Verifier.query(settings.kuaidi100Key, trackingNum)
                        Log.d(TAG, "Kuaidi100 verify: success=${res.success} code=${res.pickUpCode} address=${res.pickUpAddress} err=${res.errorMsg}")
                        if (res.success && res.pickUpCode != null) {
                            val ocrCodes = allResults.map { it.code }
                            if (ocrCodes.contains(res.pickUpCode)) {
                                Log.d(TAG, "Kuaidi100 confirm: OCR码 ${res.pickUpCode} 与 API 一致 ✓")
                            } else {
                                Log.d(TAG, "Kuaidi100 mismatch: OCR=${ocrCodes}, API=${res.pickUpCode}")
                            }
                            if (res.pickUpAddress.isNullOrBlank().not()) {
                                val rec = db.codeHistoryDao().findByCodeAndType(res.pickUpCode, CodeExtractor.CodeType.pickup_parcel.name)
                                if (rec != null && rec.pickupAddress.isBlank()) {
                                    db.codeHistoryDao().update(rec.copy(pickupAddress = res.pickUpAddress))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Kuaidi100 verify error: ${e.message}")
                    }
                }
            }
        }
    }

    /** 该类型是否被用户关闭（抽取共用，避免在多个分支重复 switch） */
    private fun isTypeDisabled(type: CodeExtractor.CodeType, settings: AppPreferences.Settings): Boolean = when (type) {
        CodeExtractor.CodeType.pickup_food -> !settings.enableFoodCodes
        CodeExtractor.CodeType.pickup_parcel -> !settings.enableParcelCodes
    }

    /** 降采样解码分享图片：先读尺寸按 inSampleSize 缩放，避免 4000×3000 全尺寸解码 OOM。 */
    private fun decodeSampledBitmap(context: Context, uri: Uri): Bitmap? {
        // 第一遍：只读边界拿尺寸（不分配像素）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 计算采样倍数：目标最长边 ~1600px（OCR 分辨率足够，兼顾内存）
        var sample = 1
        var dim = maxOf(bounds.outWidth, bounds.outHeight)
        while (dim / 2 >= 1600) { sample *= 2; dim /= 2 }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
