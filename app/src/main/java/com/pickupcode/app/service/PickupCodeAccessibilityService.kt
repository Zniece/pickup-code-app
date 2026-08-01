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
import com.pickupcode.app.extractor.CouponDetector
import com.pickupcode.app.geocoder.GeocoderVerifier
import com.pickupcode.app.kuaidi100.Kuaidi100Verifier
import com.pickupcode.app.learner.PatternLearner
import com.pickupcode.app.notification.CodeNotificationManager
import com.pickupcode.app.ocr.OCREngine
import com.pickupcode.app.preferences.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.async
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
    }

    // 实例级协程作用域：随服务实例创建/销毁，onUnbind 时 cancel 避免跨重建累积泄漏（H2）
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // 复用单例主线程 Handler：heartbeat 自续 + onAccessibilityEvent 延时调度共用，便于统一 removeCallbacks（H3/M2）
    private val mainHandler = Handler(Looper.getMainLooper())
    // 截图回调线程池：模块级单例，避免每次 captureAndExtract 新建线程泄漏（M7）
    private val screenshotExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

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

        mainHandler.postDelayed(heartbeat, 3000)
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (triggerRequested.getAndSet(false)) {
                Log.d(TAG, "心跳兜底扫描")
                performScan("手动触发")
            }
            mainHandler.postDelayed(this, 3000)
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // 收敛协程与 Handler，避免服务卸载后空转/泄漏（H2/H3）
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        screenshotExecutor.shutdownNow()
        // 释放 ML Kit 客户端（unbind 未必紧跟 destroy，提前释放避免 native 累积）
        try { OCREngine.close() } catch (_: Exception) {}
        try { CouponDetector.close() } catch (_: Exception) {}
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        screenshotExecutor.shutdownNow()
        // 释放 ML Kit 客户端，避免 native 资源随服务重建累积泄漏
        try { OCREngine.close() } catch (_: Exception) {}
        try { CouponDetector.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (triggerRequested.getAndSet(false)) {
            Log.d(TAG, "磁贴触发，延迟扫描")
            mainHandler.postDelayed({
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
                mainHandler.postDelayed({
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
        // 无障碍树递归 + 正则 + 学习文件 IO 均放 IO/Default，避免旧设备主线程卡顿
        scope.launch(Dispatchers.Default) {
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
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(s: ScreenshotResult) {
                    val buf = s.hardwareBuffer
                    try {
                        val hwBmp = Bitmap.wrapHardwareBuffer(buf, s.colorSpace)
                            ?: return showResult("截屏失败")
                        // 深拷贝为普通位图：wrapHardwareBuffer 返回的位图依赖 buf 存活，
                        // 若在 OCR 异步读取前 close buf 会读到已释放缓冲。拷贝后即可安全 close（H1）
                        val bmp = hwBmp.copy(Bitmap.Config.ARGB_8888, false)
                        hwBmp.recycle()

                        val timestamp = System.currentTimeMillis()
                        val path = saveScreenshot(bmp, timestamp)

                        scope.launch(Dispatchers.IO) {
                            try {
                                val lines = OCREngine.recognize(bmp)
                                val settings = AppPreferences.observe(this@PickupCodeAccessibilityService).first()
                                // 券码检测（需在 recycle 前用同一张 bitmap）
                                val coupons = if (settings.enableCouponCodes) {
                                    CouponDetector.detect(bmp)
                                } else emptyList()
                                bmp.recycle()
                                val allText = lines.joinToString("\n") { it.text }
                                tryExtract(allText, lines, path, settings, source, coupons)
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

    private suspend fun tryExtract(allText: String, ocrLines: List<OCREngine.TextLine>, screenshotPath: String, settings: AppPreferences.Settings, source: String, coupons: List<CouponDetector.CouponResult> = emptyList()) {
        val allResults = mutableListOf<Pair<String, CodeExtractor.CodeType>>()
        val codeSources = mutableMapOf<String, String>()

        // 券码：检测到二维码/条码并解码，code = 解码内容（不需要 OCR）
        var hasCoupon = false
        if (settings.enableCouponCodes) {
            for (c in coupons) {
                val v = c.rawValue?.trim()
                if (v.isNullOrBlank()) continue
                val key = "$v|${CodeExtractor.CodeType.coupon}"
                if (allResults.any { "${it.first}|${it.second}" == key }) continue
                allResults.add(v to CodeExtractor.CodeType.coupon)
                codeSources[v] = "券码"
                hasCoupon = true
            }
        }

        // 识别到券码后互斥：不再做取餐码/取件码的识别与标注
        val aiDeferred = if (!hasCoupon && settings.enableAI && settings.apiKey.isNotBlank()) {
            scope.async(Dispatchers.IO) {
                AIExtractor.extract(allText, settings.apiKey, settings.apiBaseUrl, settings.apiModel)
            }
        } else null

        // 正则（无券码时运行）
        if (!hasCoupon) {
            val regexResults = CodeExtractor.extract(ocrLines, resources.displayMetrics.heightPixels, this)
            for (re in regexResults) {
                if (re.confidence >= settings.confidenceThreshold && isTypeEnabled(re.type, settings)) {
                    allResults.add(re.code to re.type)
                    codeSources[re.code] = re.source
                }
            }
        }

        // 合并 AI 结果：与正则同码同 type 直接去重；不同 type 才留给下方冲突提示（问题2）
        var aiErr: String? = null
        if (aiDeferred != null) {
            try {
                val aiRes = aiDeferred.await()
                aiErr = aiRes.error
                if (aiRes.error != null) {
                    Log.w(TAG, "AI 识别失败: ${aiRes.error}")
                }
                for (ai in aiRes.results) {
                    if (!isTypeEnabled(ai.type, settings)) continue
                    // 同码同 type 已有（正则或其它 AI 项）→ 跳过；否则加入
                    val alreadySame = allResults.any { it.first == ai.code && it.second == ai.type }
                    if (alreadySame) continue
                    allResults.add(ai.code to ai.type)
                    codeSources.putIfAbsent(ai.code, ai.source)
                }
            } catch (e: Exception) {
                aiErr = e.message ?: "AI同步异常"
                Log.w(TAG, "AI 结果合并异常: ${e.message}")
            }
        }

        // Extract address (parcel scenario)
        val address = CodeExtractor.extractAddress(ocrLines, allText)

        // Map verification (async, fire-and-forget)
        if (settings.enableMapVerify && address.isNotBlank()) {
            scope.launch {
                val result = GeocoderVerifier.verify(
                    this@PickupCodeAccessibilityService, address,
                    amapApiKey = settings.amapApiKey.ifBlank { null }
                )
                Log.d(TAG, "Map verify: verified=${result.verified}, confidence=${result.confidence}, provider=${result.provider}, address=$address")
                if (result.verified) {
                    PatternLearner.recordAddressVerified(
                        this@PickupCodeAccessibilityService, address, result.confidence
                    )
                }
            }
        }

        if (allResults.isEmpty()) {
            // 问题5：若正则未识别到且 AI 也失败，提示里带上失败原因（用户有感知）
            if (settings.enableAI && settings.apiKey.isNotBlank()) {
                if (aiErr != null) showResult("未识别到取餐码/取件码 · AI识别失败(${aiErr.take(40)})")
                else showResult("未识别到取餐码/取件码")
            } else {
                showResult("未识别到取餐码/取件码")
            }
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

        // 快递100 验证：识别到取件码时，用运单号反查取件码/地址作为标准答案，对照 OCR 结果（fire-and-forget）
        if (settings.enableKuaidi100 && settings.kuaidi100Key.isNotBlank()) {
            val trackingNum = CodeExtractor.findOrderNumber(allText)
            if (trackingNum != null) {
                scope.launch {
                    val res = Kuaidi100Verifier.query(settings.kuaidi100Key, trackingNum)
                    Log.d(TAG, "Kuaidi100 verify: success=${res.success} code=${res.pickUpCode} station=${res.pickUpStation} address=${res.pickUpAddress} err=${res.errorMsg}")
                    if (res.success && res.pickUpCode != null) {
                        val ocrCodes = allResults.map { it.first }
                        val matched = ocrCodes.any { it == res.pickUpCode }
                        if (matched) {
                            Log.d(TAG, "Kuaidi100 confirm: OCR码 ${res.pickUpCode} 与 API 一致 ✓")
                        } else {
                            Log.d(TAG, "Kuaidi100 mismatch: OCR=${ocrCodes}, API=${res.pickUpCode}")
                        }
                        // 若 OCR 未识别出地址，且 API 返回了标准地址，补全到该取件码记录
                        if (address.isBlank() && !res.pickUpAddress.isNullOrBlank()) {
                            val dao = AppDatabase.getInstance(this@PickupCodeAccessibilityService).codeHistoryDao()
                            val rec = dao.findByCodeAndType(res.pickUpCode, CodeExtractor.CodeType.pickup_parcel.name)
                            if (rec != null && rec.pickupAddress.isBlank()) {
                                dao.update(rec.copy(pickupAddress = res.pickUpAddress))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isTypeEnabled(type: CodeExtractor.CodeType, settings: AppPreferences.Settings): Boolean {
        return when (type) {
            CodeExtractor.CodeType.pickup_food -> settings.enableFoodCodes
            CodeExtractor.CodeType.pickup_parcel -> settings.enableParcelCodes
            CodeExtractor.CodeType.coupon -> settings.enableCouponCodes
        }
    }

    private fun saveCode(code: String, type: CodeExtractor.CodeType, source: String, screenshotPath: String, raw: String, address: String = "") {
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
        mainHandler.post {
            val nm = getSystemService(android.app.NotificationManager::class.java) ?: return@post
            // 频道只需创建一次，但重复 create 是幂等的（同名频道会复用），保留以自取
            nm.createNotificationChannel(android.app.NotificationChannel(
                CHANNEL_ID, "结果", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(9998, NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("一键闪记").setContentText(msg)
                .setAutoCancel(true).setTimeoutAfter(3000).build())
        }
    }
}
