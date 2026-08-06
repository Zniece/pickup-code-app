package com.pickupcode.app.share

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.pickupcode.app.data.AppDatabase
import com.pickupcode.app.data.CodeHistory
import com.pickupcode.app.extractor.AIExtractor
import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.extractor.AddressExtractor
import com.pickupcode.app.extractor.BrandResolver
import com.pickupcode.app.extractor.CouponDetector
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

    // 常见分享来源 app 包名 → 可读名（无包名的兜底映射）
    private val KNOWN_SOURCE_PACKAGES = mapOf(
        "com.tencent.mm" to "微信",
        "com.tencent.mobileqq" to "QQ",
        "com.sankuai.meituan" to "美团",
        "com.sankuai.meituan.takeoutnew" to "美团外卖",
        "me.ele" to "饿了么",
        "com.taobao.taobao" to "淘宝",
        "com.xingin.xhs" to "小红书",
        "com.ss.android.ugc.aweme" to "抖音",
        "com.tencent.wework" to "企业微信",
        "com.android.bluetooth" to "蓝牙",
        "com.android.gallery3d" to "相册",
        "com.miui.gallery" to "相册",
        "com.huawei.photos" to "相册",
        "com.vivo.gallery" to "相册",
        "com.oppo.gallery" to "相册",
        "com.android.documentsui" to "文件",
        "com.google.android.apps.photos" to "Google相册",
        "com.samsung.android.app.simplesharing" to "三星分享"
    )

    /**
     * 解析本次分享的来源 App（包名 + 可读名）。
     * 优先用 ClipData 中 content URI 的 authority 查 provider 包名；
     * 兜底用 EXTRA_REFERRER 的 host。拿不到则返回空对。
     */
    private data class ShareSource(val pkg: String, val name: String)

    /** 兼容 API 33 前取 Parcelable 分享流：新签名需 API 33，老设备回退旧重载（minSdk 26）。 */
    @Suppress("DEPRECATION")
    private fun getStreamUri(intent: Intent): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun resolveShareSource(context: Context, intent: Intent?): ShareSource {
        if (intent == null) return ShareSource("", "")
        val pm = context.packageManager
        var pkg = ""

        // 1) ClipData 中第一个 content URI 的 authority → 查提供方 provider 包名
        try {
            val clip = intent.clipData
            val streamUri: Uri? = getStreamUri(intent)
            val uri: Uri? = when {
                clip != null && clip.itemCount > 0 -> clip.getItemAt(0).uri
                streamUri != null -> streamUri
                else -> null
            }
            if (uri != null && uri.scheme == "content" && !uri.authority.isNullOrBlank()) {
                val auth = uri.authority
                if (auth != null) {
                    val provider = pm.resolveContentProvider(auth, 0)
                    val pk = provider?.packageName
                    if (!pk.isNullOrBlank()) pkg = pk
                }
            }
        } catch (_: Exception) {
        }

        // 2) authority 本身含包名（部分系统 provider）
        if (pkg.isBlank()) {
            try {
                val clip = intent.clipData
                val authority = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).uri?.authority else null
                if (!authority.isNullOrBlank() && authority.contains(".") && isPackageInstalled(pm, authority)) pkg = authority
            } catch (_: Exception) {
            }
        }
        // 3) EXTRA_REFERRER host
        if (pkg.isBlank()) {
            try {
                // 带 Class 参数的重载仅 API 33+；老设备走旧重载，否则抛 NoSuchMethodError（LinkageError，catch 不到）
                val referrer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_REFERRER, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_REFERRER)
                }
                if (referrer != null && !referrer.host.isNullOrBlank()) pkg = referrer.host ?: ""
            } catch (_: Exception) {
            }
        }
        if (pkg.isBlank()) return ShareSource("", "")
        return ShareSource(pkg, packageLabel(pm, pkg))
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
        return try { pm.getApplicationInfo(pkg, 0); true } catch (_: Exception) { false }
    }

    /** 包名 → 可读 app 名（优先已知映射表，回退已安装 label，最后回退包名） */
    private fun packageLabel(pm: PackageManager, pkg: String): String {
        KNOWN_SOURCE_PACKAGES[pkg]?.let { return it }
        return try {
            val info: ApplicationInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info)?.toString() ?: pkg
        } catch (_: Exception) {
            pkg
        }
    }

    /**
     * 公开：启动指定包名的 App（供卡片/详情页的 🚪 跳转使用）。
     * 包名为空或未安装时静默失败。
     */
    fun openApp(context: Context, pkg: String) {
        if (pkg.isBlank()) return
        try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "openApp failed for $pkg: ${e.message}")
        }
    }

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
        val src = resolveShareSource(context, intent)
        if (isProcessText) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            if (!text.isNullOrBlank()) processText(context, text, "TextSelection", scope, src)
        } else when {
            intent.type?.startsWith("text/") == true -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) processText(context, text, "SharedText", scope, src)
            }
            intent.type?.startsWith("image/") == true -> {
                val uri: Uri? = getStreamUri(intent)
                if (uri != null) processImage(context, uri, "SharedImage", scope, src)
            }
        }
    }

    private suspend fun processText(
        context: Context, text: String, sourceLabel: String, scope: CoroutineScope,
        shareSource: ShareSource?
    ) {
        val lines = text.lines().map { line ->
            OCREngine.TextLine(text = line.trim(), boundingBox = null, confidence = 1.0f)
        }.filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return
        val allText = lines.joinToString(" ") { it.text }
        val address = AddressExtractor.extractAddress(lines, allText)
        extractAndNotify(context, lines, "$sourceLabel | ${lines.joinToString(" ") { it.text }}", "", address, scope, shareSource = shareSource)
    }

    private suspend fun processImage(
        context: Context, uri: Uri, sourceLabel: String, scope: CoroutineScope,
        shareSource: ShareSource?
    ) {
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

        // OCR + 券码检测（都在 recycle 前用同一张 bitmap）
        var lines: List<OCREngine.TextLine> = emptyList()
        var coupons: List<CouponDetector.CouponResult> = emptyList()
        withContext(Dispatchers.Default) {
            try {
                lines = OCREngine.recognize(bitmap)
                coupons = CouponDetector.detect(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "OCR failed: ${e.message}")
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        // 无 OCR 文本且无券码 → 无内容
        if (lines.isEmpty() && coupons.isEmpty()) return
        val allText = lines.joinToString(" ") { it.text }
        val address = AddressExtractor.extractAddress(lines, allText)
        val snippet = "$sourceLabel | ${lines.joinToString(" ") { it.text }}"
        extractAndNotify(context, lines, snippet, screenshotPath, address, scope, coupons, shareSource)
    }

    private suspend fun extractAndNotify(
        context: Context,
        lines: List<OCREngine.TextLine>,
        rawSnippet: String,
        screenshotPath: String = "",
        address: String = "",
        scope: CoroutineScope,
        coupons: List<CouponDetector.CouponResult> = emptyList(),
        shareSource: ShareSource? = null
    ) {
        val shareSourcePkg = shareSource?.pkg ?: ""
        val shareSourceName = shareSource?.name ?: ""
        val allText = lines.joinToString(" ") { it.text }
        val db = AppDatabase.getInstance(context)
        val settings = withContext(Dispatchers.IO) { AppPreferences.observe(context).first() }
        val allResults = mutableListOf<CodeExtractor.ExtractedCode>()

        // 券码：检测到二维码/条码并解码，code = 解码内容；不需要正则
        var hasCoupon = false
        if (settings.enableCouponCodes) {
            for (c in coupons) {
                val v = c.rawValue?.trim()
                if (v.isNullOrBlank()) continue
                if (allResults.any { it.code == v && it.type == CodeExtractor.CodeType.coupon }) continue
                allResults.add(CodeExtractor.ExtractedCode(v, CodeExtractor.CodeType.coupon, "券码", 1.0f))
                hasCoupon = true
            }
        }

        // 识别到券码后互斥：不再做取餐码/取件码的识别与标注（避免券码+OCR码重复/误标）
        if (!hasCoupon) {
            // 正则主路径先行（问题3：分享路径接入 AI，但不阻塞）
            val regexResults = withContext(Dispatchers.Default) { CodeExtractor.extract(lines, context = context, source = "share") }
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
        }

        if (allResults.isEmpty()) {
            Log.d(TAG, "No pickup code found")
            return
        }

        for (result in allResults) {
            // 多驿站：每个码取自己通知卡片区域的地址；取不到再回退全屏地址
            val perCodeAddr = AddressExtractor.extractAddressForCode(lines, result.code)
            val effAddr = perCodeAddr.ifBlank { address }
            // 原始去重语义：查重后照常新增，让同一码多次保存产生多行，进「重复值整理」手动整理
            val save = db.codeHistoryDao().insertCheckDuplicate(CodeHistory(
                code = result.code,
                type = result.type.name,
                source = result.source,
                rawTextSnippet = rawSnippet,
                pickupAddress = effAddr,
                screenshotPath = screenshotPath,
                shareSourcePkg = shareSourcePkg,
                shareSourceName = shareSourceName,
                timestamp = System.currentTimeMillis()
            ))
            val id = save.id

            // Notify user (同码同type已存在 -> 提示重复；否则正常通知)
            if (save.existed) {
                val dupCount = db.codeHistoryDao().countDuplicateGroups()
                CodeNotificationManager.showDuplicate(
                    context, result.code, result.type, result.source, id, dupCount
                )
            } else {
                CodeNotificationManager.show(context, result.code, result.type, result.source, id)
            }
            Log.d(TAG, "Recognized: ${result.code} (${result.type.name}) from ${result.source}${if (save.existed) " [DUPLICATE]" else ""}")

            // Async address geocoding verification
            if (address.isNotBlank() && settings.enableMapVerify) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val geoResult = GeocoderVerifier.verify(
                            context, address,
                            amapApiKey = settings.amapApiKey.ifBlank { null }
                        )
                        if (geoResult.verified) {
                            // M1: 定向更新 geo 字段，不动 code/address 等其他字段，避免覆盖用户编辑
                            db.codeHistoryDao().findByCodeAndType(result.code, result.type.name)?.let { rec ->
                                db.codeHistoryDao().updateGeo(
                                    rec.id, true,
                                    geoResult.confidence,
                                    geoResult.formattedAddress ?: ""
                                )
                            }
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
            val trackingNum = BrandResolver.findOrderNumber(allText)
            if (trackingNum != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val res = Kuaidi100Verifier.query(settings.kuaidi100Key, trackingNum, Kuaidi100Verifier.guessCourierCode(trackingNum))
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
        CodeExtractor.CodeType.coupon -> !settings.enableCouponCodes
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
