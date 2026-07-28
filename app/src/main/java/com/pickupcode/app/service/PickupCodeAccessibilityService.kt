package com.pickupcode.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

class PickupCodeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PickupCodeA11y"
        private const val CHANNEL_ID = "pickup_code_result"

        @JvmField
        val triggerRequested = AtomicBoolean(false)

        private val AUTO_SCAN_PACKAGES = setOf(
            "com.meituan", "com.sankuai", "me.ele", "com.eg.android",
            "com.kfc", "com.mcdonalds", "com.cainiao",
            "com.taobao.taobao", "com.jingdong.app.mall", "com.pinduoduo",
        )

        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    }

    private var lastAutoScanPkg: String? = null
    private var lastAutoScanTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "无障碍服务已连接")

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        serviceInfo = info

        Handler(Looper.getMainLooper()).postDelayed(heartbeat, 3000)
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (triggerRequested.getAndSet(false)) {
                Log.d(TAG, "心跳兜底扫描")
                performScan("手动触发")
            }
            Handler(Looper.getMainLooper()).postDelayed(this, 3000)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (triggerRequested.getAndSet(false)) {
            Log.d(TAG, "磁贴触发，延迟扫描")
            Handler(Looper.getMainLooper()).postDelayed({
                performScan("手动触发")
            }, 1200)
            return
        }

        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            val now = System.currentTimeMillis()
            if (pkg == lastAutoScanPkg && now - lastAutoScanTime < 3000) return
            if (AUTO_SCAN_PACKAGES.any { pkg.startsWith(it) }) {
                lastAutoScanPkg = pkg
                lastAutoScanTime = now
                Log.d(TAG, "自动扫描: $pkg")
                Handler(Looper.getMainLooper()).postDelayed({
                    performScan("自动检测: $pkg")
                }, 800)
            }
        }
    }

    override fun onInterrupt() {}

    private fun performScan(source: String) {
        Log.d(TAG, "开始扫描: $source")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureAndExtract(source)
        } else {
            performScanFromText(source)
        }
    }

    /** 仅节点文字模式（API < 30兜底），不存截图 */
    private fun performScanFromText(source: String) {
        scope.launch {
            val settings = AppPreferences.observe(this@PickupCodeAccessibilityService).first()
            val allText = collectAllText()
            val lines = allText.lines().map { OCREngine.TextLine(it, null, null) }
            tryExtract(allText, lines, "", settings, source)
        }
    }

    private fun collectAllText(): String {
        val root = rootInActiveWindow ?: return ""
        val lines = collectNodeText(root)
        root.recycle()
        return lines.joinToString("\n")
    }

    private fun collectNodeText(node: AccessibilityNodeInfo): List<String> {
        val lines = mutableListOf<String>()
        if (node.text != null) {
            node.text.toString().trim().takeIf { it.length >= 2 }?.let { lines.add(it) }
        }
        if (node.contentDescription != null) {
            val desc = node.contentDescription.toString().trim()
            val txt = node.text?.toString()?.trim()
            if (desc.length >= 2 && desc != txt) lines.add(desc)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                lines.addAll(collectNodeText(child))
                child.recycle()
            }
        }
        return lines
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureAndExtract(source: String) {
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            java.util.concurrent.Executors.newSingleThreadExecutor(),
            object : TakeScreenshotCallback {
                override fun onSuccess(s: ScreenshotResult) {
                    val buf = s.hardwareBuffer
                    try {
                        val bmp = Bitmap.wrapHardwareBuffer(buf, s.colorSpace)
                            ?: return showResult("截屏失败")

                        val timestamp = System.currentTimeMillis()
                        val path = saveScreenshot(bmp, timestamp)

                        scope.launch(Dispatchers.IO) {
                            try {
                                val lines = OCREngine.recognize(bmp)
                                bmp.recycle()
                                val allText = lines.joinToString("\n") { it.text }
                                val settings = AppPreferences.observe(this@PickupCodeAccessibilityService).first()
                                tryExtract(allText, lines, path, settings, source)
                            } catch (e: Exception) {
                                Log.e(TAG, "OCR失败", e)
                                try { bmp.recycle() } catch (_: Exception) {}
                                showResult("识别出错")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "截屏失败", e)
                        showResult("识别出错")
                    } finally { buf.close() }
                }

                override fun onFailure(code: Int) { showResult("截屏失败($code)") }
            })
    }

    private suspend fun tryExtract(allText: String, ocrLines: List<OCREngine.TextLine>, screenshotPath: String, settings: AppPreferences.Settings, source: String) {
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

            saveCode(code, type, codeSources[code] ?: "unknown", screenshotPath, source)
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

    private fun saveCode(code: String, type: CodeExtractor.CodeType, source: String, screenshotPath: String, raw: String) {
        scope.launch {
            val db = AppDatabase.getInstance(this@PickupCodeAccessibilityService)
            val dao = db.codeHistoryDao()

            // 查重：同 code+type 已有记录则更新，否则新增
            val existing = dao.findByCodeAndType(code, type.name)
            val id = if (existing != null) {
                // 更新已有记录：刷新时间戳、截图、来源，恢复为活跃
                dao.update(existing.copy(
                    timestamp = System.currentTimeMillis(),
                    screenshotPath = screenshotPath.ifEmpty { existing.screenshotPath },
                    source = source,
                    rawTextSnippet = raw,
                    isActive = true,
                    doneAt = 0
                ))
                existing.id
            } else {
                dao.insert(CodeHistory(
                    code = code, type = type.name,
                    source = source,
                    screenshotPath = screenshotPath,
                    rawTextSnippet = raw
                ))
            }

            // 检测同 code 不同类型的重复值
            val otherType = if (type == CodeExtractor.CodeType.pickup_food)
                CodeExtractor.CodeType.pickup_parcel else CodeExtractor.CodeType.pickup_food
            val duplicates = dao.findSameCodeDifferentType(code, type.name)

            if (duplicates.isNotEmpty()) {
                val typeLabel = if (type == CodeExtractor.CodeType.pickup_food) "取餐" else "取件"
                val otherLabel = if (otherType == CodeExtractor.CodeType.pickup_food) "取餐" else "取件"
                showResult("⚠️ 「$code」同时出现在${otherLabel}和${typeLabel}中，请进入App确认")
            }

            CodeNotificationManager.show(this@PickupCodeAccessibilityService, code, type, source, id)
        }
    }

    private fun saveScreenshot(bmp: Bitmap, timestamp: Long): String {
        try {
            val dir = File(filesDir, "screenshots")
            dir.mkdirs()
            val file = File(dir, "screenshot_$timestamp.jpg")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            return file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存截屏失败", e)
            return ""
        }
    }

    private fun showResult(msg: String) {
        Handler(Looper.getMainLooper()).post {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(android.app.NotificationChannel(
                CHANNEL_ID, "结果", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(9998, NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("一键闪记").setContentText(msg)
                .setAutoCancel(true).setTimeoutAfter(3000).build())
        }
    }
}
