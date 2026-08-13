package com.pickupcode.app.share

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.pickupcode.app.BuildConfig
import com.pickupcode.app.data.AppDatabase
import com.pickupcode.app.data.CodeHistory
import com.pickupcode.app.extractor.AIExtractor
import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.extractor.AddressExtractor
import com.pickupcode.app.extractor.BrandResolver
import com.pickupcode.app.extractor.CouponDetector
import com.pickupcode.app.geocoder.GeocoderVerifier
import com.pickupcode.app.kuaidi100.Kuaidi100Verifier
import com.pickupcode.app.ocr.OCREngine
import com.pickupcode.app.preferences.AppPreferences
import com.pickupcode.app.service.PostVerifier
import com.pickupcode.app.service.RecognitionPipeline
import com.pickupcode.app.util.ImageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                // B2: host 可能是任意字符串（非包名），须校验已安装，否则后续 getLaunchIntentForPackage 静默返回 null
                val host = referrer?.host
                if (!host.isNullOrBlank() && isPackageInstalled(pm, host)) pkg = host
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
        val pm = context.packageManager

        // 兜底：尝试启动目标包；launch intent 存在则启动，否则返回 false
        fun launchFromPkg(target: String): Boolean {
            return try {
                val intent = pm.getLaunchIntentForPackage(target)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    Log.i(TAG, "openApp launched: $target")
                    true
                } else {
                    Log.w(TAG, "openApp: no launcher intent for $target")
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "openApp launch failed for $target: ${e.message}")
                false
            }
        }

        // 1) 直接启动来源包（最常见）
        if (launchFromPkg(pkg)) return

        // 2) 媒体/文档 provider（无主 Activity 的系统服务提供者）→ 映射到真实相册类 App
        val mediaProviders = setOf(
            "com.google.android.providers.media.module", // AOSP/Google
            "com.android.providers.media",
            "com.android.providers.media.documents",
            "com.google.android.apps.photos"
        )
        if (pkg in mediaProviders) {
            val galleryCandidates = listOf(
                "com.google.android.apps.photos", // 自家
                "com.miui.gallery",               // 小米
                "com.huawei.photos",              // 华为
                "com.vivo.gallery",               // vivo/iQOO
                "com.oppo.gallery",               // OPPO
                "com.android.gallery3d"           // AOSP 兜底
            )
            for (c in galleryCandidates) {
                if (launchFromPkg(c)) return
            }
        }

        // 3) 终极兜底：让系统挑一个能处理相册的 App
        try {
            val selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
            selector.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(selector)
            Log.i(TAG, "openApp: launched gallery fallback")
        } catch (e: Exception) {
            Log.w(TAG, "openApp fallback gallery failed: ${e.message}")
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
        // Low-4: 分享文本可能极长（整页复制/长文），截断到 20000 字符再处理，避免 OCR/正则/存储无界增长
        val clipped = text.take(20000)
        val lines = clipped.lines().map { line ->
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
                ImageUtils.decodeSampledBitmap(context, uri)
            } catch (e: Exception) {
                Log.e(TAG, "Read image failed: ${e.message}")
                null
            }
        } ?: return

        // Save shared image as screenshot for detail page
        val screenshotPath = withContext(Dispatchers.IO) {
            ImageUtils.saveJpeg(context, "shared_images", "share", bitmap)
        }

        // OCR + 券码检测（都在 recycle 前用同一张 bitmap）
        var lines: List<OCREngine.TextLine> = emptyList()
        var coupons: List<CouponDetector.CouponResult> = emptyList()
        withContext(Dispatchers.Default) {
            try {
                lines = OCREngine.recognize(bitmap)
                coupons = CouponDetector.detect(bitmap)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "OCR failed", e)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        if (lines.isEmpty() && coupons.isEmpty()) {
            Log.w(TAG, "分享图片识别无结果——OCR和条码检测均未返回内容")
            return
        }
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

        // 金融/支付噪音拦截：银行/支付/转账等通知截图里的数字（金额/验证码/余额）极易被当取件码。
        // 命中金融词且无快递/取件信号词 → 整段不识别。（参考同类产品实现 isExpressRelatedSms）
        if (CodeExtractor.isFinancialNoise(allText)) {
            Log.d(TAG, "金融/支付噪音文本，跳过识别")
            return
        }

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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "AI 结果合并异常: ${e.message}")
                }
            }
        }

        if (allResults.isEmpty()) {
            Log.d(TAG, "No pickup code found")
            return
        }

        // 逐码落库 + 站点学习（三路径共用管线）
        val saved = RecognitionPipeline.finalize(
            context = context,
            allResults = allResults.map { it.code to it.type },
            codeSources = allResults.associate { it.code to it.source },
            lines = lines,
            allText = allText,
            fullAddress = address,
            rawSnippet = rawSnippet,
            screenshotPath = screenshotPath,
            shareSourcePkg = shareSourcePkg,
            shareSourceName = shareSourceName,
            repo = db.repository
        )
        // 通知（同码同 type 已存在 → 重复提示；否则正常通知）
        for (s in saved) {
            RecognitionPipeline.notifySaved(context, { db.repository.countDuplicateGroups() },
                s.code, s.type, s.source, s.id, s.existed)
            RecognitionPipeline.logSaved(TAG, s.code, s.type, s.source, address, s.existed)
        }
        // Low-3: 记录本次保存的 code → id，供快递100回填定向更新，避免命中历史最新行
        val savedIdsByCode = saved.associate { it.code to it.id }

        // Async address geocoding verification（每个码）
        if (address.isNotBlank() && settings.enableMapVerify) {
            for (s in saved) {
                scope.launch(Dispatchers.IO) {
                    try {
                        PostVerifier.verifyMap(context, address, settings.amapApiKey.ifBlank { null }) { conf, fmtAddr ->
                            // M1: 定向更新 geo 字段，不动 code/address 等其他字段，避免覆盖用户编辑
                            db.repository.findByCodeAndType(s.code, s.type.name)?.let { rec ->
                                db.repository.updateGeo(rec.id, true, conf, fmtAddr ?: "")
                            }
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
                        val res = PostVerifier.verifyKuaidi100(context, settings.kuaidi100Key, trackingNum, allResults.map { it.code })
                            ?: return@launch
                        val pCode = res.pickUpCode ?: return@launch
                        if (res.pickUpAddress.isNullOrBlank().not()) {
                            // Low-3: 优先定向更新本次保存的记录（同码可能有多行历史，findByCodeAndType 会命中错误行）
                            val targetId = savedIdsByCode[pCode]
                            if (targetId != null) {
                                db.codeHistoryDao().getByIdSuspend(targetId)?.let { rec ->
                                    if (rec.pickupAddress.isBlank()) {
                                        db.codeHistoryDao().update(rec.copy(pickupAddress = res.pickUpAddress))
                                    }
                                }
                            } else {
                                val rec = db.codeHistoryDao().findByCodeAndType(pCode, CodeExtractor.CodeType.pickup_parcel.name)
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
}
