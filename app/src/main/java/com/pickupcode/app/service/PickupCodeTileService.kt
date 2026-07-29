package com.pickupcode.app.service

import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.pickupcode.app.data.AppDatabase
import com.pickupcode.app.data.CodeHistory
import com.pickupcode.app.extractor.AIExtractor
import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.notification.CodeNotificationManager
import com.pickupcode.app.ocr.OCREngine
import com.pickupcode.app.preferences.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class PickupCodeTileService : TileService() {

    companion object {
        private const val TAG = "PickupCodeTile"
        private const val REQUEST_CODE_CAPTURE = 1001

        // 保存 MediaProjection 凭证，Activity 传回来后使用
        private var pendingProjectionResult: Pair<Int, Intent>? = null

        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "磁贴被点击")

        // 如果有已激活的投影，直接截图
        if (mediaProjection != null) {
            Log.d(TAG, "使用已有的 MediaProjection")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                captureAndExtract()
            }
            return
        }

        // 检查是否有之前保存的 MediaProjection 凭证
        if (pendingProjectionResult != null) {
            Log.d(TAG, "使用已保存的投影凭证")
            val (resultCode, data) = pendingProjectionResult!!
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startProjection(resultCode, data)
            }
            return
        }

        // 需要授权 — 启动透明 Activity 请求权限
        Log.d(TAG, "需要获取截屏授权")
        CapturePermissionActivity.setCallback { resultCode, data ->
            pendingProjectionResult = resultCode to data
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startProjection(resultCode, data)
            }
        }
        val intent = Intent(this, CapturePermissionActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(intent)
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        releaseProjection()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startProjection(resultCode: Int, data: Intent) {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection 停止")
                mediaProjection = null
                pendingProjectionResult = null
            }
        }, null)

        captureAndExtract()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureAndExtract() {
        val proj = mediaProjection ?: run {
            showResult("截屏失败：未授权")
            return
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        Log.d(TAG, "开始截屏: ${width}x$height @ ${density}dpi")

        imageReader = ImageReader.newInstance(width, height, android.graphics.ImageFormat.JPEG, 1)
        virtualDisplay = proj.createVirtualDisplay(
            "PickupCodeCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        // 使用回调监听截图可用，比固定延迟更可靠
        val handler = Handler(Looper.getMainLooper())
        imageReader?.setOnImageAvailableListener({ reader ->
            handler.removeCallbacksAndMessages(null)
            val image = reader.acquireLatestImage()
            if (image == null) {
                Log.w(TAG, "未获取到截图")
                showResult("识别出错")
                cleanupCapture()
                return@setOnImageAvailableListener
            }

            val buf = image.hardwareBuffer
            val bmp = Bitmap.wrapHardwareBuffer(buf, image.colorSpace)
            image.close()

            if (bmp == null) {
                showResult("截屏失败")
                cleanupCapture()
                return@setOnImageAvailableListener
            }

            val timestamp = System.currentTimeMillis()
            val path = saveScreenshot(bmp, timestamp)

            scope.launch(Dispatchers.IO) {
                try {
                    val lines = OCREngine.recognize(bmp)
                    val allText = lines.joinToString("\n") { it.text }
                    val settings = AppPreferences.observe(this@PickupCodeTileService).first()
                    tryExtract(allText, lines, path, settings, "磁贴截屏")
                } catch (e: Exception) {
                    Log.e(TAG, "OCR 失败", e)
                    showResult("识别出错")
                } finally {
                    try { bmp.recycle() } catch (_: Exception) {}
                    cleanupCapture()
                }
            }
        }, handler)

        // 兜底超时：3秒后还没收到截图则报错
        handler.postDelayed({
            if (imageReader != null) {
                Log.w(TAG, "截屏超时")
                showResult("识别超时")
                cleanupCapture()
            }
        }, 3000)
    }

    private fun cleanupCapture() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
    }

    private fun releaseProjection() {
        cleanupCapture()
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        pendingProjectionResult = null
    }

    private suspend fun tryExtract(
        allText: String,
        ocrLines: List<OCREngine.TextLine>,
        screenshotPath: String,
        settings: AppPreferences.Settings,
        source: String
    ) {
        val allResults = mutableListOf<Pair<String, CodeExtractor.CodeType>>()
        val codeSources = mutableMapOf<String, String>()

        // AI（需要总开关开启 + API Key 非空）
        if (settings.enableAI && settings.apiKey.isNotBlank()) {
            val aiResults = AIExtractor.extract(allText, settings.apiKey, settings.apiBaseUrl, settings.apiModel)
            for (ai in aiResults) {
                if (isTypeEnabled(ai.type, settings)) {
                    allResults.add(ai.code to ai.type)
                    codeSources[ai.code] = ai.source
                }
            }
        }

        // 正则（总是运行，不因AI阻断）
        val regexResults = CodeExtractor.extract(ocrLines, resources.displayMetrics.heightPixels)
        for (re in regexResults) {
            if (re.confidence >= settings.confidenceThreshold && isTypeEnabled(re.type, settings)) {
                allResults.add(re.code to re.type)
                codeSources[re.code] = re.source
            }
        }

        // 提取地址（取件场景）
        val address = CodeExtractor.extractAddress(ocrLines, allText)

        if (allResults.isEmpty()) {
            showResult("未识别到取餐码/取件码")
            return
        }

        // 去重：同码值同类型只保留一个；同码值不同类型保留但通知用户
        val seen = mutableSetOf<String>()
        val conflicts = mutableListOf<String>()
        for ((code, type) in allResults) {
            val key = "$code|$type"
            if (key in seen) continue
            seen.add(key)

            // 检查是否有冲突（同码不同type）
            val otherType = if (type == CodeExtractor.CodeType.pickup_food)
                CodeExtractor.CodeType.pickup_parcel else CodeExtractor.CodeType.pickup_food
            if ("$code|$otherType" in seen || allResults.any { it.first == code && it.second == otherType }) {
                conflicts.add(code)
            }

            saveCode(code, type, codeSources[code] ?: "unknown", screenshotPath, source, address)
        }

        // 有冲突时通知用户自行判断
        if (conflicts.isNotEmpty()) {
            showResult("⚠️ 「${conflicts.joinToString("、")}」同时匹配取餐/取件类型，请进入App确认")
        }
    }

    private fun isTypeEnabled(type: CodeExtractor.CodeType, settings: AppPreferences.Settings): Boolean {
        return when (type) {
            CodeExtractor.CodeType.pickup_food -> settings.enableFoodCodes
            CodeExtractor.CodeType.pickup_parcel -> settings.enableParcelCodes
        }
    }

    private fun saveCode(code: String, type: CodeExtractor.CodeType, source: String, screenshotPath: String, raw: String, address: String = "") {
        scope.launch {
            val db = AppDatabase.getInstance(this@PickupCodeTileService)
            val dao = db.codeHistoryDao()

            // 查重：同 code+type 已有记录则更新，否则新增
            val existing = dao.findByCodeAndType(code, type.name)
            val id = if (existing != null) {
                dao.update(existing.copy(
                    timestamp = System.currentTimeMillis(),
                    screenshotPath = screenshotPath.ifEmpty { existing.screenshotPath },
                    source = source,
                    rawTextSnippet = raw,
                    pickupAddress = address.ifBlank { existing.pickupAddress },
                    isActive = true,
                    doneAt = 0
                ))
                existing.id
            } else {
                dao.insert(CodeHistory(
                    code = code, type = type.name,
                    source = source,
                    screenshotPath = screenshotPath,
                    rawTextSnippet = raw,
                    pickupAddress = address
                ))
            }

            // 检测同 code 不同类型的重复值
            val duplicates = dao.findSameCodeDifferentType(code, type.name)

            if (duplicates.isNotEmpty()) {
                val typeLabel = if (type == CodeExtractor.CodeType.pickup_food) "取餐" else "取件"
                val otherLabel = if (type == CodeExtractor.CodeType.pickup_food) "取件" else "取餐"
                showResult("⚠️ 「$code」同时出现在${otherLabel}和${typeLabel}中，请进入App确认")
            }

            CodeNotificationManager.show(this@PickupCodeTileService, code, type, source, id)
        }
    }

    private fun saveScreenshot(bmp: Bitmap, timestamp: Long): String {
        return try {
            val dir = File(filesDir, "screenshots")
            dir.mkdirs()
            val file = File(dir, "screenshot_$timestamp.jpg")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存截屏失败", e)
            ""
        }
    }

    private fun showResult(msg: String) {
        Handler(Looper.getMainLooper()).post {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            val channelId = "pickup_code_result"
            nm.createNotificationChannel(android.app.NotificationChannel(
                channelId, "结果", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(9998, NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("一键闪记").setContentText(msg)
                .setAutoCancel(true).setTimeoutAfter(3000).build())
        }
    }
}
