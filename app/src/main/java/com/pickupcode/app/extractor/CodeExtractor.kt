package com.pickupcode.app.extractor

import android.graphics.Rect
import android.content.Context
import android.util.Log
import com.pickupcode.app.learner.PatternLearner
import com.pickupcode.app.ocr.OCREngine

object CodeExtractor {

    data class ExtractedCode(val code: String, val type: CodeType, val source: String, val confidence: Float)
    enum class CodeType { pickup_food, pickup_parcel, coupon }
    enum class StationType { LOCKER, PICKUP_POINT, UNKNOWN }
    data class PickupLocation(
        val stationName: String,
        val stationType: StationType,
        val cabinetNumber: String?,
        val fullAddress: String
    )

    private val THREE_SEGMENT_PARCEL = Regex("\\b(\\d{1,3})-(\\d{1,2})-(\\d{3,6})\\b")
    private val FOUR_SEGMENT_PARCEL = Regex("\\b([A-Za-z]?\\d{1,2})-(\\d{1,2})-(\\d{1,2})-(\\d{2,4})\\b")
    private val LETTER_TWO_SEGMENT_PARCEL = Regex("\\b([A-Z])-(\\d{1,2})-(\\d{3,4})\\b", RegexOption.IGNORE_CASE)
    private val LETTER_DASH_FIVE_PARCEL = Regex("\\b([A-Za-z])-?(\\d{5,6})\\b", RegexOption.IGNORE_CASE)
    // A8-3-3315: letter prefix + 3 dash-separated segments, e.g. locker codes (A/B/C prefix)
    private val LETTER_THREE_SEG_PARCEL = Regex("\\b([A-Za-z]\\d{1,2})-(\\d{1,2})-(\\d{3,6})\\b", RegexOption.IGNORE_CASE)
    private val LETTER_DASH_THREE_PARCEL = Regex("\\b([A-Za-z])-(\\d{3,4})\\b", RegexOption.IGNORE_CASE)
    private val LONG_NUMBER_PARCEL = Regex("\\b(\\d{6,8})\\b")
    private val LETTER_NUMBER_FOOD = Regex("\\b([A-Z]\\s*[-]?\\s*\\d{2,4})\\b", RegexOption.IGNORE_CASE)
    private val PURE_NUMBER_FOOD = Regex("\\b(?<![\\d-])(\\d{2,5})(?![\\d])\\b")
    private val PREFIXED_CODE = Regex("(取[餐件货单]码|取餐号|取单号|排号|券号|提取码)[:：]?\\s*(?:为|是)?\\s*([A-Za-z0-9\\-]{2,12})")
    // 菜鸟/驿站类通知标准句式：凭1-6-5020到...取（件）；容忍 OCR 在码值与方位词间插入空格
    private val PING_CODE = Regex("(?:凭|好评码|提取码|券号)[:：]?\\s*([A-Za-z0-9\\-]{2,12}?)\\s*(?=(?:到|至|去|领|取|在|格|号柜|菜鸟|驿站|快递柜))", RegexOption.IGNORE_CASE)
    private val ADDR_PIPE_FULL = Regex("[路街巷弄号栋幢单元柜室楼区县镇乡村庄]")
    private val ADDR_LANDMARK = Regex("(菜鸟|驿站|快递柜|丰巢|超市|诊所|对面|门口|小区|大厦|医院|银行|学校|商场)")
    private val ADDR_AFTER_TO = Regex("到(.+?)(领取|取件|门店|取运单尾号|取运单|取您|取你的|取貨|取货|取走|取你)")
    private val ADDR_LABEL = Regex("地址[:：]\\s*(.+)")
    private val ADDR_PLACED = Regex("(?:已放至|已暂存至|已放入|送达)\\s*([^，,。.\\n]{4,80})")
    private val CABINET_NUM = Regex("(\\d+)号柜")
    private val PAREN_ADDR = Regex("\\uFF08([^\\uFF09]*[路街段柜])\\uFF09")
    private val PING_NOISE_TRAIL = Regex("凭\\s*[A-Za-z0-9\\-]+\\s*$")
    private val NEXT_LINE_CODE = Regex("^\\s*([A-Za-z0-9\\-]{2,12})\\s*$")
    private val CODE_KEYWORD_NEAR = Regex("(取[件餐货]码|取餐号|驿站|快递柜|自提柜|取件点)")
    private val ORDER_LONG_SQL = Regex("\\b\\d{6,}-\\d{5,}\\b")
    private val ORDER_SHORT_SQL = Regex("\\b\\d{2,4}-\\d{3,4}-\\d{4,}\\b")

    // 运单号字母前缀匹配（社区规则，见 orderNumPrefixToBrand）
    private val REG_JP = Regex("^JP\\d{10,15}$")
    private val REG_JT = Regex("^JT\\d{10,15}$")
    private val REG_JD = Regex("^(JD|JDL)\\d{10,15}$")
    private val REG_JD_VA = Regex("^[VA]\\d{10,15}$")
    private val REG_SF = Regex("^SF\\d{10,15}$")
    private val REG_YT = Regex("^YT\\d{10,15}$")
    private val REG_YTO = Regex("^YTO\\d{8,14}$")
    private val REG_YD = Regex("^YD\\d{10,15}$")
    private val REG_YUNDA = Regex("^YUNDA\\d{8,14}$")
    private val REG_ZTO = Regex("^ZTO\\d{8,14}$")
    private val REG_ST = Regex("^ST\\d{10,15}$")
    private val REG_STO = Regex("^STO\\d{8,14}$")
    private val REG_EMS = Regex("^E[A-Z0-9]\\d{9}CN$")
    private val REG_POSTAL = Regex("^RA\\d{9,13}CN$")
    private val REG_DPK = Regex("^DPK\\d{10,15}$")
    private val REG_DPL = Regex("^DPL\\d{10,15}$")

    private const val SCORE_PREFIXED = 100f; private const val SCORE_THREE_SEG = 95f
    private const val SCORE_FOUR_SEG = 95f
    private const val SCORE_LETTER_TWO_SEG = 85f
    private const val SCORE_LETTER_DASH_FIVE = 85f
    private const val SCORE_LETTER_DASH_THREE = 80f
    private const val SCORE_LETTER_NUM_FOOD = 80f
    private const val SCORE_PURE_NUM_FOOD = 75f; private const val SCORE_LONG_NUM_PARCEL = 60f
    private const val PATTERN_PREFIXED = "PREFIXED_CODE"
    private const val LARGE_FONT_HEIGHT_PX = 60; private const val FONT_SIZE_RATIO_THRESHOLD = 1.5f

    // PING_CODE（凭条号）评分：略低于前缀码，命中驿站/快递柜或三段式码再加分
    private const val PING_BASE_PENALTY = 2f
    private const val PING_PARCEL_BONUS = 8f
    private const val PING_MULTISEG_BONUS = 10f

    private val BRACKET_BRAND = Regex("【([^】]+)】")

    private val FOOD_BRAND_KEYWORDS = listOf(
        // 咖啡
        "瑞幸", "luckin", "星巴克", "starbucks", "库迪", "cotti", "manner", "seesaw", "挪瓦咖啡", "nowwa", "幸运咖",
        // 快餐/西式
        "麦当劳", "mcdonald", "肯德基", "kfc", "德克士", "dicos", "汉堡王", "burger king", "华莱士", "塔斯汀", "必胜客", "pizza", "达美乐", "domino", "萨莉亚", "赛百味", "subway",
        // 茶饮/新式茶
        "喜茶", "heytea", "奈雪", "奈雪的茶", "蜜雪冰城", "霸王茶姬", "茶百道", "一点点", "coco", "都可", "书亦烧仙草", "书亦", "古茗", "茶颜悦色", "沪上阿姨", "甜啦啦", "益禾堂", "林里", "linlee", "茉莉奶白", "乐乐茶", "贡茶", "tims", "tims天好咖啡",
        // 中式快餐/粉面
        "老乡鸡", "真功夫", "沙县小吃", "兰州拉面", "兰州牛肉面", "杨国福", "张亮麻辣烫", "麻辣烫", "吉野家", "味千拉面", "和府捞面", "李先生", "老娘舅", "大米先生", "乡村基",
        // 烘焙/甜品/小吃
        "鲍师傅", "好利来", "味多美", "巴黎贝甜", "面包新语",
        // 火锅/正餐/其他连锁
        "海底捞", "呷哺呷哺", "西贝", "西贝莜面村", "外婆家", "绿茶餐厅", "探鱼", "半天妖", "太二",
        // 卤味/鸡排等小吃连锁
        "正新鸡排", "正新", "绝味", "绝味鸭脖", "煌上煌", "紫燕百味鸡", "周黑鸭"
    )
    private val FOOD_KEYWORDS = FOOD_BRAND_KEYWORDS + listOf(
        "取餐", "取餐码", "取餐号", "取单码", "取单号", "请取餐", "正在制作", "等待取餐"
    )
    private val PARCEL_KEYWORDS = listOf(
        "菜鸟", "驿站", "丰巢", "妈妈驿站", "兔喜", "免喜", "快递超市",
        "京东快递", "顺丰", "中通", "圆通", "申通", "韵达", "极兔", "邮政",
        "取件码", "取货码", "提取码", "快递柜", "货架", "韵达超市", "欢猫智柜"
    )
    private val COURIER_BRANDS = listOf(
        "京东快递", "顺丰", "中通", "圆通", "申通", "韵达", "极兔",
        "邮政快递", "邮政", "菜鸟", "丰巢", "妈妈驿站", "兔喜",
        "免喜", "韵达超市", "欢猫智柜"
    )

    private val STATION_TYPE_MAP = mapOf(
        "丰巢" to StationType.LOCKER, "欢猫智柜" to StationType.LOCKER,
        "快递柜" to StationType.LOCKER,
        "菜鸟驿站" to StationType.PICKUP_POINT, "妈妈驿站" to StationType.PICKUP_POINT,
        "兔喜" to StationType.PICKUP_POINT, "免喜" to StationType.PICKUP_POINT,
        "快递超市" to StationType.PICKUP_POINT, "韵达超市" to StationType.PICKUP_POINT,
        "代收点" to StationType.PICKUP_POINT
    )

    private val EXCLUDE_PATTERNS = listOf(
        Regex("\\b1[3-9]\\d{9}\\b"), Regex("\\b0\\d{2,3}-?\\d{7,8}\\b"),
        Regex("\\d{1,2}:\\d{2}"), Regex("[￥¥\$]\\s*\\d+"), Regex("\\d+\\.?\\d*\\s*[元块]"),
        Regex("\\b\\d{12,}\\b"), Regex("\\b\\d{4}年\\d{1,2}月\\b"),
        Regex("\\d+\\s*[个份件杯张条]"), Regex("\\d+\\s*[分钟小时]"), Regex("\\d+\\s*[号桌台]"),
        Regex("\\d+\\s*[号楼层室]"), Regex("\\d+\\s*[折]"), Regex("\\d+\\s*[分](?![钟])"),
        Regex("\\d+\\s*[毫厘克千克升毫升]"),
        // 容量/重量/尺寸等规格（英文单位后缀），如 120ml / 500ML / 2kg / 15cm — 不是取件码
        Regex("\\b\\d+(?:\\.\\d+)?\\s*(?:ml|ML|mL|l|L|g|kg|mg|cm|mm|km|GB|MB|KB|TB)\\b"),
        Regex("\\b\\d{4}-\\d{1,2}\\b"), // date suffix like 1124-15
        Regex("\\b\\d{6,8}-\\d{5,}\\b"), // full order number
        Regex("\\b[xX]\\d{1,2}\\b") // shopping cart quantity marker (x1, x2, ...) — not a pickup code
    )

    // ---------------------------------------------------------------
    // Code extraction
    // ---------------------------------------------------------------

    fun extract(lines: List<OCREngine.TextLine>, screenHeight: Int = 0, context: Context? = null, source: String = "screen"): List<ExtractedCode> {
        val candidates = mutableListOf<Candidate>()
        val allText = lines.joinToString(" ") { it.text }
        val isFoodContext = FOOD_KEYWORDS.any { allText.contains(it, ignoreCase = true) }
        val isParcelContext = PARCEL_KEYWORDS.any { allText.contains(it) }
        val avgFontHeight = lines.mapNotNull { it.boundingBox?.height()?.toFloat() }
            .takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f

        for (i in lines.indices) {
            val line = lines[i]
            // 单行匹配
            PREFIXED_CODE.find(line.text)?.let { m ->
                val code = m.groupValues[2]
                if (!isExcluded(code)) {
                    val p = m.groupValues[1]
                    candidates.add(Candidate(code,
                        if (p.contains("餐") || p.contains("单")) CodeType.pickup_food else CodeType.pickup_parcel,
                        SCORE_PREFIXED, sourceFromLine(line, p, lines, allText)))
                }
            }
            // 跨行：OCR 常把「取件码/凭取」拆成两行（如 上一行结尾「取」+ 本行「件码067865」）
            if (i > 0) {
                val prev = lines[i - 1].text.trim()
                // 仅当本行以裸前缀字+码开头（件/餐/货/单+码）且无空格分隔，才尝试拼接上一行尾字
                if (line.text.trim().matches(Regex("^[餐件货单]码[A-Za-z0-9].*"))) {
                    val joined = prev.takeLast(1) + line.text.trim()
                    PREFIXED_CODE.find(joined)?.let { m ->
                        val code = m.groupValues[2]
                        if (!isExcluded(code)) {
                            val p = m.groupValues[1]
                            candidates.add(Candidate(code,
                                if (p.contains("餐") || p.contains("单")) CodeType.pickup_food else CodeType.pickup_parcel,
                                SCORE_PREFIXED, sourceFromLine(line, p, lines, allText)))
                        }
                    }
                }
            }
        }

        val prefixKw = listOf("取餐码", "取餐号", "取单码", "取单号", "取件码", "取货码", "排号", "提取码")
        for (i in lines.indices) {
            if (prefixKw.any { lines[i].text.contains(it, ignoreCase = true) } && i + 1 < lines.size) {
                val nextLine = lines[i + 1].text.trim()
                // Match pure numbers or letter-dash-number codes on the next line
                val nextMatch = NEXT_LINE_CODE.find(nextLine)
                if (nextMatch != null && !isExcluded(nextMatch.groupValues[1])) {
                    val code = nextMatch.groupValues[1]
                    val isFood = lines[i].text.contains("餐") || lines[i].text.contains("单")
                    candidates.add(Candidate(code,
                        if (isFood) CodeType.pickup_food else CodeType.pickup_parcel,
                        SCORE_PREFIXED, sourceFromLine(lines[i], if (isFood) "取餐码" else "取件码", lines, allText)))
                }
            }
        }

        data class Rule(val regex: Regex, val type: CodeType, val baseScore: Float,
                        val ctxBonus: Float = 0f, val sizeBonus: Boolean = false, val pureNum: Boolean = false,
                        val minMatchLen: Int = 0)

        // 凭条号句式（凭1-6-5020到...取）：菜鸟驿站/快递柜典型通知，优先且绕过 food 上下文干扰
        for (line in lines) {
            PING_CODE.findAll(line.text).forEach matchLoop@{ m ->
                val code = m.groupValues[1]
                if (isExcluded(code) || code.length < 2) return@matchLoop
                var s = SCORE_PREFIXED - PING_BASE_PENALTY
                if (PARCEL_KEYWORDS.any { line.text.contains(it) }) s += PING_PARCEL_BONUS
                if (THREE_SEGMENT_PARCEL.matches(code) || FOUR_SEGMENT_PARCEL.matches(code)) s += PING_MULTISEG_BONUS
                candidates.add(Candidate(code, CodeType.pickup_parcel, s,
                    sourceFromLine(line, "凭条号", lines, allText)))
            }
        }

        val rules = mutableListOf(
            Rule(THREE_SEGMENT_PARCEL, CodeType.pickup_parcel, SCORE_THREE_SEG),
            Rule(FOUR_SEGMENT_PARCEL, CodeType.pickup_parcel, SCORE_FOUR_SEG),
            Rule(LETTER_TWO_SEGMENT_PARCEL, CodeType.pickup_parcel, SCORE_LETTER_TWO_SEG),
            Rule(LETTER_DASH_FIVE_PARCEL, CodeType.pickup_parcel, SCORE_LETTER_DASH_FIVE),
            Rule(LETTER_THREE_SEG_PARCEL, CodeType.pickup_parcel, SCORE_THREE_SEG),
            Rule(LETTER_DASH_THREE_PARCEL, CodeType.pickup_parcel, SCORE_LETTER_DASH_THREE),
            Rule(LONG_NUMBER_PARCEL, CodeType.pickup_parcel, SCORE_LONG_NUM_PARCEL, 10f),
            Rule(LETTER_NUMBER_FOOD, CodeType.pickup_food, SCORE_LETTER_NUM_FOOD, 10f, true),
            Rule(PURE_NUMBER_FOOD, CodeType.pickup_food, SCORE_PURE_NUM_FOOD, 10f, true, true)
        )

        // Load auto-learned patterns
        // B3: 记住"编译后 pattern -> 存储用 regex 字符串"，命中时用来 touchRule 刷新 lastUsedAt
        val regexToLearned = mutableMapOf<String, String>()
        if (context != null) {
            val learned = com.pickupcode.app.learner.PatternLearner.getLearnedPatterns(context)
            for (rule in learned) {
                // A1: 用户停用的规则不再参与识别；B3: 衰减降级的规则也不再强制应用
                if (!rule.enabled || rule.decayed) continue
                try {
                    val regex = Regex(rule.regex)
                    val type = if (rule.type == "pickup_food") CodeType.pickup_food else CodeType.pickup_parcel
                    // Auto-learned patterns are lower confidence: require the match to be >= 3 chars
                    // to avoid over-broad learned rules matching 2-char noise like X1 / A1.
                    rules.add(Rule(regex, type, 65f, 10f, minMatchLen = 3))
                    regexToLearned[regex.pattern] = rule.regex
                } catch (_: Exception) { /* skip invalid regex */ }
            }
        }

        for (line in lines) {
            val pos = posBonus(line, screenHeight)
            val size = sizeBonus(line, avgFontHeight)
            for (rule in rules) {
                rule.regex.findAll(line.text).forEach matchLoop@{ m ->
                    if (isExcluded(m.value, context)) return@matchLoop
                    // Auto-learned rules: reject over-short matches (e.g. X1 / A1 2-char noise)
                    if (rule.minMatchLen > 0 && m.value.length < rule.minMatchLen) return@matchLoop
                    var s = rule.baseScore + pos
                    if (rule.sizeBonus) s += size

                    if (rule.pureNum) {
                        val n = m.value.length
                        val kw = FOOD_KEYWORDS.any { line.text.contains(it, ignoreCase = true) }
                        val big = avgFontHeight > 0 && line.boundingBox != null &&
                            line.boundingBox.height() > avgFontHeight * FONT_SIZE_RATIO_THRESHOLD
                        if (n <= 2 && !kw && !big) return@matchLoop
                        if (n == 5) s += 15f
                        if (isFoodContext) {
                            if (kw || big) s += 10f
                            else if (line.boundingBox != null && line.boundingBox.height() > LARGE_FONT_HEIGHT_PX) s += 5f
                            else s -= 35f
                        } else if (!kw && !big) return@matchLoop
                    }

                    val ctxOk = when (rule.type) { CodeType.pickup_food -> isFoodContext; CodeType.pickup_parcel -> isParcelContext; CodeType.coupon -> false }
                    if (ctxOk) s += rule.ctxBonus
                    val conflict = when (rule.type) { CodeType.pickup_food -> isParcelContext && !isFoodContext; CodeType.pickup_parcel -> isFoodContext && !isParcelContext; CodeType.coupon -> false }
                    if (conflict) s -= 8f

                    // B3: 命中已学规则 → 刷新其 lastUsedAt 并解除衰减（用于"这条规则最近还被用到吗"衰减机制）
                    if (context != null && rule.baseScore == 65f && m.value.length >= 3) {
                        regexToLearned[rule.regex.pattern]?.let { r ->
                            PatternLearner.touchRule(context, r)
                        }
                    }

                    candidates.add(Candidate(m.value, rule.type, s, sourceFromLine(line,
                        if (rule.type == CodeType.pickup_food) "food" else "parcel", lines, allText)))
                }
            }
        }

        if (candidates.isEmpty()) return emptyList()

        if (isParcelContext && !isFoodContext) candidates.replaceAll { c -> if (c.type == CodeType.pickup_food) c.copy(score = c.score - 50f) else c }
        if (isFoodContext && !isParcelContext) candidates.replaceAll { c -> if (c.type == CodeType.pickup_parcel) c.copy(score = c.score - 50f) else c }
        val hasMultiseg = candidates.any { it.type == CodeType.pickup_parcel && (THREE_SEGMENT_PARCEL.matches(it.code) || FOUR_SEGMENT_PARCEL.matches(it.code)) }
        if (hasMultiseg) candidates.replaceAll { c -> if (c.type == CodeType.pickup_parcel && LONG_NUMBER_PARCEL.matches(c.code)) c.copy(score = c.score - 40f) else c }
        val hasOrder = allText.contains(ORDER_LONG_SQL) || allText.contains(ORDER_SHORT_SQL)
        if (hasOrder) {
            candidates.replaceAll { c ->
                if (LONG_NUMBER_PARCEL.matches(c.code)) c.copy(score = c.score - 50f)
                else if (c.type == CodeType.pickup_parcel && c.code.all { it.isDigit() }) c.copy(score = c.score - 30f)
                else c
            }
        }

        val codeKeywordLines = lines.filter { it.text.contains(CODE_KEYWORD_NEAR) }
        if (codeKeywordLines.isNotEmpty()) {
            candidates.replaceAll { c ->
                val nearKeyword = codeKeywordLines.any { kw ->
                    val lineIdx = lines.indexOf(kw)
                    val candidateLineIdx = lines.indexOfFirst { it.text.contains(c.code) }
                    candidateLineIdx >= 0 && kotlin.math.abs(lineIdx - candidateLineIdx) <= 2
                }
                if (nearKeyword) c.copy(score = c.score + 15f) else c
            }
        }

        candidates.sortByDescending { it.score }
        // 仅 Debug 构建输出诊断日志（生产裁剪掉逐行 dump + 候选遍历，避免每次识别的 IO/日志开销）
        if (com.pickupcode.app.BuildConfig.DEBUG && context != null) {
            // 逐行 OCR 结构 dump：看 TextLine 是怎么拆行的（跨行粘连/拆断是很多误报的根源）
            lines.forEachIndexed { idx, tl ->
                val bb = tl.boundingBox
                val bbS = if (bb != null) "(x=${bb.left},y=${bb.top},w=${bb.width()},h=${bb.height()})" else "(no-box)"
                android.util.Log.d("CodeExtrDiag", "LINE[$idx] $bbS conf=${tl.confidence} @ ${tl.text}")
            }
            android.util.Log.d("CodeExtrDiag", "hasOrder=" + (allText.contains(ORDER_LONG_SQL) || allText.contains(ORDER_SHORT_SQL)) + " allText=" + allText)
            for (it in candidates) {
                // 补上匹配到的原文上下文 + 所在行号，便于定位是哪个规则、哪段文本捕的
                val lineIdx = lines.indexOfFirst { l -> l.text.contains(it.code) }
                val ctx = if (lineIdx >= 0) lines[lineIdx].text else "?"
                android.util.Log.d("CodeExtrDiag", "cand: code=${it.code} score=${it.score} type=${it.type} src=${it.source} line=$lineIdx ctx=$ctx")
            }
        }
        val seen = mutableSetOf<String>()
        val results = mutableListOf<ExtractedCode>()
        val top = candidates.firstOrNull()?.score ?: 0f
        for (c in candidates) {
            if (c.code in seen) continue; seen.add(c.code)
            if (c.score >= top * 0.75f)
                results.add(ExtractedCode(c.code, c.type, c.source, (c.score / SCORE_PREFIXED).coerceIn(0f, 1f)))
        }
        if (context != null) recordLearning(context, results, allText, source)
        return results
    }

    // ---------------------------------------------------------------
    // Address extraction (structured)
    // ---------------------------------------------------------------

    fun extractLocation(lines: List<OCREngine.TextLine>, allText: String): PickupLocation {
        var stationName = ""
        var fullAddress = ""
        var cabinet: String? = null
        var addrFrom = "none"

        // S-Coupon: 券码/到店券的门店识别（最高优先级）
        // 场景：外卖/到店券（德克士、蜜雪冰城等）截图，用户要的是“适用门店”（如 蜜雪冰城(老十字街店)），
        // 而非配送地址/周边地址。信号：待使用/用券/到店取/适用门店/立即用券/到店使用/券号 等券码上下文。
        val couponContext = listOf(
            "券号", "用券", "到店取", "到店使用", "待使用", "适用门店", "立即用券",
            "再次使用", "已使用", "兑换", "代金券", "优惠券", "满减券"
        )
        val isCouponContext = couponContext.any { allText.contains(it) }
        if (isCouponContext && fullAddress.isEmpty()) {
            // 优先扫描逐行，找“品牌名(店名)”格式（蜜雪冰城(老十字街店) / 德克士(郸械万果园店)）
            // 注意：括号字符类易触发 ICU 正则“incorrectly nested parentheses”，
            // 故不用单条大正则，改用简单匹配 + 字符串定位，稳妥且兼容。
            var couponAddr = ""
            var couponStation = ""
            for (line in lines) {
                val t = line.text.trim()
                // 用全角/半角开括号定位门店串：形如 品牌(店名) 或 品牌（店名）
                val openIdx = t.indexOfAny(charArrayOf('(', '（'))
                if (openIdx < 1) continue
                val afterOpen = t.substring(openIdx + 1)
                // 闭括号位置（全角/半角）
                val closeP = afterOpen.indexOf(')'); val closeF = afterOpen.indexOf('）')
                val closeIdx = when {
                    closeP >= 0 && closeF >= 0 -> minOf(closeP, closeF)
                    closeP >= 0 -> closeP
                    closeF >= 0 -> closeF
                    else -> -1
                }
                if (closeIdx <= 0) continue
                val brandPart = t.substring(0, openIdx).trim()
                val paren = afterOpen.substring(0, closeIdx).trim()
                // 品牌前导需为 2~10 个汉字；括号名需以“店”结尾且不含数字（排除快递员电话括号）
                if (!brandPart.matches(Regex("[\\u4e00-\\u9fff]{2,10}"))) continue
                if (!paren.endsWith("店") || paren.any { it.isDigit() }) continue
                // 完整门店串 = t 中从行首到闭括号的整段（基于原始行重建，避免 trimmed paren 导致丢字）
                val full = t.substring(0, openIdx) + t[openIdx] + afterOpen.substring(0, closeIdx + 1)
                // 行内门店信号：命中（已通过品牌前导校验的前提下）再认。
                // 注意：品牌前导校验已足够窄，此处要求额外的“到店/适用门店/营业中”这类券码信号，
                // 以提高精确度——避免把正文里任意 “X(某店)” 当门店（PRD：只认券码截图的门店）。
                val brandHits = FOOD_BRAND_KEYWORDS.any { brandPart.contains(it, ignoreCase = true) }
                val storeSig =
                    t.contains("营业中") || t.contains("适用门店") || t.contains("到店") ||
                        t.contains("用券") || t.contains("门店") || brandHits
                if (!storeSig) continue
                couponAddr = full
                couponStation = full
                break
            }
            if (couponAddr.isNotEmpty()) {
                fullAddress = couponAddr.take(80)
                stationName = couponStation
                addrFrom = "SCoupon-store"
                // 若 isAddressLike 校验不通过（如门店串太短/不含地址特征），仍保留但降级以不改后续逻辑
            }
        }

        // S1: 【】 bracket brand for station name
        // 优先取含站点/快递关键词的括号；跳过快递员姓名+电话的括号（如【刘趁义:19037835253】）
        // 注意：不设“取第一个括号”的兜底——否则快递员括号会误当站名，留空交给后面的分支补全
        val bracketMatches = BRACKET_BRAND.findAll(allText).map { it.groupValues[1].trim() }.toList()
        val goodBracket = bracketMatches.firstOrNull { content ->
            // 跳过包含手机号/运单号等数字的括号
            if (content.any { it.isDigit() }) return@firstOrNull false
            STATION_TYPE_MAP.keys.any { content.contains(it) } ||
                ADDR_PIPE_FULL.containsMatchIn(content) ||
                ADDR_LANDMARK.containsMatchIn(content) ||
                listOf("店", "超市", "智柜", "生活", "代收", "驿站").any { content.contains(it) }
        }
        if (goodBracket != null) stationName = stripBrackets(goodBracket)

        // S0: 显式标签（取件地址/收货地址/代收点地址/取件点…）——最可靠的地址信号，最高优先级
        val explicit = listOf("取件点位置", "取件地址", "收货地址", "代收点地址", "取件点")
        for (lineIdx in lines.indices) {
            val line = lines[lineIdx]
            for (p in explicit) {
                val i = line.text.indexOf(p)
                if (i < 0) continue
                var a = line.text.substring(i + p.length).trimStart(':', '：', ' ')
                for (sep in listOf('|', '｜')) {
                    val bar = a.indexOf(sep)
                    if (bar >= 0) { if (stationName.isEmpty()) stationName = extractStationName(a.substring(0, bar).trim()); a = a.substring(bar + 1).trim() }
                }
                a = cleanAddress(a)
                // 标签后若空/太短（值可能在下一行），向下拼 1~2 行续行
                if (fullAddress.isEmpty() && !isAddressLike(a) && lineIdx + 1 < lines.size) {
                    val cont = StringBuilder()
                    for (j in lineIdx + 1 until minOf(lineIdx + 3, lines.size)) {
                        val c = lines[j].text.trim()
                        if (c.isEmpty()) continue
                        if (c.first().isDigit() || c.startsWith("|")) break
                        cont.append(c)
                    }
                    val combined = cleanAddress(a + cont.toString())
                    if (isAddressLike(combined)) a = combined
                }
                // 折叠地址补全：S0-label 抓到的地址若只到省市区层级（缺路/街/号/店等街道/网点特征），
                // 折叠地址补全：S0-label 抓到的地址可能是被 UI 折叠的短串（如“…育新北展开”或“【xx店:..”），
                // 而同屏另有更具体的完整街道地址行（快递正文，如 育新路北段爱玛电动车旁边）。
                // 判断标准：存在比 a 更长、像地址、无折叠残留(未闭合括号/省略号/展开) 且含明确街道特征的行 → 就用它替换。
                val streetLike = listOf("路", "街", "巷", "弄", "道", "号店", "小区", "苑", "大厦", "超市", "驿站", "快柜", "智柜", "村", "庄")
                val adminLike = listOf("省", "市", "县", "区")
                // 折叠残留检测：a 以未闭合括号开头（如 【xx店，右括号被截断），或含 …/.. 省略号痕迹
                val uncleanA = a.startsWith("【") || a.startsWith("（") || a.startsWith("(") ||
                    a.contains("..") || a.contains("…")
                val better = lines
                    .map { it.text.trim() }
                    .filter {
                        it.length in 6..60 &&
                            it.length > a.length &&
                            streetLike.any { s -> it.contains(s) } &&
                            adminLike.none { ad -> it.contains(ad) } &&
                            listOf("电话", "..", "拨打", "联系", "展开", "【", "（", "(").none { it2 -> it.contains(it2) } &&
                            isAddressLike(it)
                    }
                    .maxByOrNull { it.length }
                // 条件：a 有折叠残留，或（a 是地址但缺明确街道特征时，且能找到更长完整行）→ 替换
                if (better != null && better != a &&
                    (uncleanA || streetLike.none { a.contains(it) })) {
                    a = better
                }
                if (fullAddress.isEmpty() && isAddressLike(a)) {
                    fullAddress = a.take(80)
                    addrFrom = "S0-label"
                    if (stationName.isEmpty()) stationName = extractStationName(a)
                }
            }
        }
        // S0b: 地址: 后跟收货地址（如 收货地址:河南省周口市郸城县育新北…）
        if (fullAddress.isEmpty()) {
            ADDR_LABEL.find(allText)?.let { m ->
                var a = cleanAddress(m.groupValues[1])
                // 折叠地址增强：UI 常把地址折叠成短串（如 …育新路与季李庄...展开），
                // 而完整地址在同屏其它行（快递正文行，如 育新路与季庄街西李庄社区卫生所对面2号柜）。
                // 若存在包含【短地址前4字】且 isAddressLike 且更长的行，取最长者作为完整地址。
                if (isAddressLike(a) && a.length >= 4) {
                    val prefix4 = a.substring(0, 4)
                    // 候选：以短地址前缀开头、更长、且像地址的行；排除本标签行本身（它只是折叠串的载体）
                    val better = lines
                        .map { it.text.trim() }
                        .filter { it.length > a.length && it.startsWith(prefix4) && isAddressLike(it) }
                        .maxByOrNull { it.length }
                    if (better != null && better != a) a = better
                }
                if (isAddressLike(a)) {
                    fullAddress = a.take(80)
                    addrFrom = "S0b-addrLabel"
                    if (stationName.isEmpty()) stationName = extractStationName(a)
                }
            }
        }

        // S0c: 两列键值布局（5G消息卡片）——标签在左列，值在右列同一横带，地址续行在下方同列
        // 例：LINE[取件地址 y=942 x=107] + LINE[育新路北段店 y=942 x=380] + LINE[育新路…爱玛电动车 y=1032 x=380]
        if (fullAddress.isEmpty()) {
            val labelKw = listOf("取件地址", "取件点位置", "代收点地址", "取件点", "地址")
            for (labLine in lines) {
                val labBox = labLine.boundingBox ?: continue
                if (!labelKw.any { labLine.text.contains(it) }) continue
                // 找同一横带的右侧值行（y 接近 + 值在标签右边）
                val valueLine = lines.firstOrNull { v ->
                    val vb = v.boundingBox ?: return@firstOrNull false
                    vb !== labBox &&
                        kotlin.math.abs(vb.centerY() - labBox.centerY()) < 60 &&
                        vb.left > labBox.right
                } ?: continue
                // 拼接值行 + 下方同列（地址续行）
                val valueTxt = valueLine.text.trim()
                val valueBox = valueLine.boundingBox ?: continue
                val sb = StringBuilder()
                var curY = valueBox.bottom
                for (contLine in lines) {
                    val cb = contLine.boundingBox ?: continue
                    if (cb.centerY() > curY && cb.centerY() - curY < 120 &&
                        kotlin.math.abs(cb.left - valueBox.left) < 40) {
                        sb.append(contLine.text.trim())
                        curY = cb.bottom
                    }
                }
                val contTxt = sb.toString()
                // 若续行已包含取值行的核心地址（前4字），直接用更完整的续行，避免重复拼接
                // （例：取值行=育新路北段店，续行=育新路育新路育新路北段爱玛电动车旁边 → 只用续行）
                val core = if (valueTxt.length >= 4) valueTxt.substring(0, 4) else valueTxt
                val usesValue = valueTxt.length < 4 || !contTxt.contains(core)
                val a = cleanAddress(if (usesValue) (valueTxt + contTxt) else contTxt)
                if (fullAddress.isEmpty() && a.isNotEmpty() && isAddressLike(a)) {
                    fullAddress = a.take(80)
                    addrFrom = "S0c-column"
                    if (stationName.isEmpty()) stationName = extractStationName(a)
                }
            }
        }

        // S2: pipe-separated "shop | address"
        if (fullAddress.isEmpty()) {
            for (line in lines) {
                for (sep in listOf('|', '｜')) {
                    val bar = line.text.indexOf(sep)
                    if (bar < 0) continue
                    val left = line.text.substring(0, bar).trim()
                    val right = line.text.substring(bar + 1).trim()
                    if (stationName.isEmpty() && isAddressLike(left).not() && left.isNotBlank()) {
                        stationName = extractStationName(left)
                    }
                    if (fullAddress.isEmpty() && right.isNotBlank() && isAddressLike(right)) {
                        // 长地址可能被 OCR 拆到相邻多行：先向下拼 1~3 行，拼完仍像地址且非空则用拼接结果，否则退回单行
                        val parts = mutableListOf(right)
                        var cursorY = line.boundingBox?.bottom
                        for (j in lines.indexOf(line) + 1 until minOf(lines.indexOf(line) + 4, lines.size)) {
                            val n = lines[j]
                            val nBox = n.boundingBox
                            if (nBox != null && cursorY != null && nBox.top - cursorY > 600) break
                            val nt = n.text.trim()
                            if (nt.isEmpty()) continue
                            if (nt.length < 2 || nt.first().isDigit() || nt.startsWith("|") ||
                                listOf("展开", "收起", "复制", "拨打", "导航", "昨天", "今天", "消息", "通知").any { nt.contains(it) }) break
                            parts.add(nt)
                            cursorY = nBox?.bottom ?: cursorY
                        }
                        val joinedAddr = parts.joinToString("")
                        val finalAddr = if (isAddressLike(joinedAddr) && joinedAddr.length > right.length) joinedAddr else right
                        fullAddress = stripBrackets(finalAddr).take(80); addrFrom = "S2-pipe"
                    }
                }
            }
        }

        // S5: "已放至/已暂存至" pattern
        if (fullAddress.isEmpty()) {
            ADDR_PLACED.find(allText)?.let { m ->
                val a = m.groupValues[1].trim()
                if (isAddressLike(a)) { fullAddress = stripBrackets(a).take(80); addrFrom = "S5-placed" }
            }
        }

        // S6: "到...取件/领取/门店/取运单" template (SMS/APP style)
        // Scope to lines containing 凭/取件 to avoid greedy match across unrelated 到 in joined text.
        // 合并自 S6a（单行）+ S6b（跨行）：先试单行，单行失败再向前拼 1~3 行，触发词判断与后处理复用同一套。
        if (fullAddress.isEmpty()) {
            for (i in lines.indices) {
                val line = lines[i]
                val hasTrigger = line.text.contains("凭") || line.text.contains("取件") || line.text.contains("取运单") ||
                    line.text.contains("取您") || line.text.contains("取你的") || line.text.contains("取走")
                if (!hasTrigger) continue

                // S6a: 单行内匹配
                ADDR_AFTER_TO.find(line.text)?.let { m6 ->
                    val a = m6.groupValues[1].trim()
                    val clean = a.replace(PING_NOISE_TRAIL, "").trim()
                    if (isAddressLike(clean)) {
                        fullAddress = stripBrackets(clean).take(80)
                        addrFrom = "S6a"
                        if (stationName.isEmpty()) stationName = extractStationName(clean)
                        return@let
                    }
                }
                if (fullAddress.isNotEmpty()) break

                // S6b: 跨行匹配——OCR 常把「到<地址>」和「取运单…」拆成多个 TextLine，
                // 拼接前 1~3 行（结束词「取您/取件」可能在 3 行外）
                for (span in 1..3) {
                    if (i - span < 0) break
                    val start = i - span
                    val combined = (start until i).joinToString(" ") { lines[it].text } + " " + line.text
                    ADDR_AFTER_TO.find(combined)?.let { m6 ->
                        val a = m6.groupValues[1].trim()
                        val clean = a.replace(PING_NOISE_TRAIL, "").trim()
                        if (isAddressLike(clean)) {
                            fullAddress = stripBrackets(clean).take(80)
                            addrFrom = "S6b"
                            if (stationName.isEmpty()) stationName = extractStationName(clean)
                            return@let
                        }
                    }
                }
                if (fullAddress.isNotEmpty()) break
            }
        }

        // S7: cabinet number + address from "号柜" line
        for (idx in lines.indices) {
            val line = lines[idx]
            if (!line.text.contains("号柜")) continue
            CABINET_NUM.find(line.text)?.let { cabinet = it.groupValues[1] }
            var s7addr = stripBrackets(line.text.trim())
            // 若本行不够像地址（柜号行常只有「2号柜」），向上拼 1~2 行的地址前缀
            if (!isAddressLike(s7addr) && idx > 0) {
                val up = StringBuilder()
                for (j in (idx - 2).coerceAtLeast(0) until idx) {
                    val u = lines[j].text.trim()
                    if (u.isEmpty()) continue
                    up.append(u)
                }
                val cand = (up.toString() + s7addr)
                if (isAddressLike(cand)) s7addr = cand.take(80)
            }
            if (fullAddress.isEmpty() && isAddressLike(s7addr))
                { fullAddress = stripBrackets(s7addr).take(80); addrFrom = "S7-cabinet" }
        }

        // S8: nearby lines after prefix keywords
        val prefixes = explicit + listOf("代收点", "地址", "号柜")
        for (i in lines.indices) {
            if (!prefixes.any { lines[i].text.contains(it) }) continue
            if (stationName.isEmpty()) stationName = extractStationName(lines[i].text)
            for (j in i + 1..minOf(i + 2, lines.lastIndex)) {
                if (fullAddress.isNotEmpty()) break
                val n = lines[j].text.trim()
                if (!isAddressLike(n)) continue
                // 命中后向下拼 1~2 行地址续行（跳过单字/数字/噪声行）
                var s8addr = stripBrackets(n).take(80)
                if (s8addr.length < 80) {
                    val contParts = mutableListOf(s8addr)
                    for (k in j + 1 until minOf(j + 3, lines.size)) {
                        val c = lines[k].text.trim()
                        if (c.isEmpty()) break
                        if (c.length < 2 || c.first().isDigit() || c.startsWith("|") ||
                            listOf("展开", "收起", "复制", "拨打", "导航", "昨天", "今天", "消息", "通知").any { c.contains(it) }) break
                        contParts.add(c)
                    }
                    val joined = contParts.joinToString("")
                    if (isAddressLike(joined)) s8addr = joined.take(80)
                }
                fullAddress = s8addr; addrFrom = "S8-nearby"; break
            }
        }

        // S9: parenthesized address
        if (fullAddress.isEmpty()) {
            for (line in lines) {
                val m = PAREN_ADDR.find(line.text)
                if (m != null && isAddressLike(m.groupValues[1])) {
                    fullAddress = stripBrackets(m.groupValues[1]).take(80); addrFrom = "S9-paren"; break
                }
            }
        }

        // S10: fallback - any line with road/street/cabinet indicators
        if (fullAddress.isEmpty()) {
            for (idx in lines.indices) {
                val line = lines[idx]
                if (stationName.isEmpty()) stationName = extractStationName(line.text)
                if (!line.text.contains(ADDR_PIPE_FULL) || !isAddressLike(line.text.trim())) continue
                // 向下续行拼接：OCR 常把地址拆成相邻多行（如 申通快/谦/申通快递）
                var addrBase = stripBrackets(line.text.trim())
                if (addrBase.length < 80) {
                    var cursorY = line.boundingBox?.bottom
                    val parts = mutableListOf(addrBase)
                    for (j in idx + 1 until minOf(idx + 4, lines.size)) {
                        val n = lines[j]
                        val nBox = n.boundingBox
                        // 纵 gap 过大（>600px）说明不是同一地址块，停止
                        if (nBox != null && cursorY != null && nBox.top - cursorY > 600) break
                        val nt = n.text.trim()
                        if (nt.isEmpty()) continue
                        // 过滤单字错字（OCR 拆出的笔画字，如 谦）与非地址噪声行
                        if (nt.length < 2) continue
                        if (nt.first().isDigit() || nt.startsWith("|") || nt.startsWith("●") ||
                            listOf("展开", "收起", "复制", "拨打", "导航", "昨天", "今天", "消息", "通知").any { nt.contains(it) }) break
                        parts.add(nt)
                        cursorY = nBox?.bottom ?: cursorY
                    }
                    val joined = parts.joinToString("")
                    if (isAddressLike(joined)) { addrBase = joined.take(80) } else { addrBase = parts.first() }
                }
                fullAddress = addrBase; addrFrom = "S10-fallback"; break
            }
        }

        // 后处理：快递柜/智能柜识别——在取件地址后追加柜名+柜号，并修正站名
        val lockerName = STATION_TYPE_MAP.entries
            .filter { it.value == StationType.LOCKER }
            .map { it.key }
            .sortedByDescending { it.length }
            .firstOrNull { allText.contains(it) }
        if (lockerName != null) {
            // 劣质站名替换：动作前缀（已放入/待取件…）或“含数字的商品/规格名”（如 4.5英寸昧碟）都不是站名 → 换成柜名
            val stationLooksBad = listOf("已放入", "已放至", "已暂存", "待取件", "待取", "已派送").any { stationName.startsWith(it) } ||
                (stationName.any { it.isDigit() } &&
                    STATION_TYPE_MAP.keys.none { stationName.contains(it) } &&
                    ADDR_PIPE_FULL.containsMatchIn(stationName).not())
            if (stationName.isNotEmpty() && stationName != lockerName && stationLooksBad) {
                stationName = lockerName
            }
            if (fullAddress.isNotEmpty() && !fullAddress.contains(lockerName)) {
                fullAddress = (fullAddress + lockerName).take(80)
            }
            // 追加柜号（如 2号柜）：地址若没有“X号柜”则补上
            if (cabinet != null && cabinet!!.isNotEmpty() &&
                !Regex("\\d+号柜").containsMatchIn(fullAddress)) {
                fullAddress = (fullAddress + cabinet + "号柜").take(80)
            }
        }

        // 折叠 OCR 重复的路名/站名（对 S2/S5/S10 等未走 cleanAddress 的路径也生效）
        fullAddress = dedupeRepeated(fullAddress)

        // Determine station type
        val stype = classifyStation(stationName, fullAddress, allText)

        // If station name still empty, try to extract from full address or all text
        if (stationName.isEmpty()) {
            stationName = extractStationName(fullAddress)
        }
        if (stationName.isEmpty()) {
            stationName = extractStationName(allText)
        }

        android.util.Log.d("CodeExtrDiag",
            "ADDR=full=[$fullAddress] from=[$addrFrom] station=[$stationName] cabinet=[$cabinet] type=[$stype] allText=" + allText)

        return PickupLocation(
            stationName = stationName.ifEmpty { "未知站点" },
            stationType = stype,
            cabinetNumber = cabinet,
            fullAddress = fullAddress
        )
    }

    /** Backward-compatible: return address string from structured location. */
    fun extractAddress(lines: List<OCREngine.TextLine>, allText: String): String {
        return extractLocation(lines, allText).fullAddress
    }

    // ---------------------------------------------------------------
    // Station helpers
    // ---------------------------------------------------------------

    private fun extractStationName(text: String): String {
        // Try 【】 first
        BRACKET_BRAND.find(text)?.let { return stripBrackets(it.groupValues[1].trim()) }
        // Try known station keywords
        for (kw in STATION_TYPE_MAP.keys) {
            if (text.contains(kw)) {
                // Extract the full station name: text before and including the keyword
                val idx = text.indexOf(kw)
                val start = (0 until idx).lastOrNull { text[it] in "，,。.；;、|｜ " }?.plus(1) ?: 0
                val end = (idx + kw.length until text.length)
                    .firstOrNull { text[it] in "，,。.；;、|｜ " } ?: text.length
                val name = text.substring(start, end).trim()
                if (name.length in 2..16) return name
            }
        }
        return ""
    }

    private fun classifyStation(stationName: String, address: String, allText: String): StationType {
        val combined = "$stationName $address $allText"
        for ((kw, type) in STATION_TYPE_MAP) {
            if (combined.contains(kw)) return type
        }
        return StationType.UNKNOWN
    }

    // ---------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------

    /** 去掉取件地址开头/结尾的【】（）包围（OCR 常把站名/地址包在全角括号里）。 */
    private fun stripBrackets(s: String): String {
        var r = s.trim()
        while (r.isNotEmpty()) {
            val c0 = r.first(); val c9 = r.last()
            if ((c0 == '【' && c9 == '】') || (c0 == '(' && c9 == ')') || (c0 == '（' && c9 == '）')) {
                r = r.substring(1, r.length - 1).trim()
            } else break
        }
        // 处理不闭合的左括号（OCR/UI 折叠截断导致如 “【育新路北段店” 无右括号）：剥掉孤立左括号
        if (r.startsWith("【") && !r.contains("】")) r = r.removePrefix("【").trim()
        if (r.startsWith("（") && !r.contains("）")) r = r.removePrefix("（").trim()
        if (r.startsWith("(") && !r.contains(")")) r = r.removePrefix("(").trim()
        return r
    }

    /** 地址尾部 UI 噪音关键词，出现时截断（展开/复制/拨打电话 等按钮文案）。 */
    private val ADDR_TRAIL_NOISE = listOf(
        "展开", "收起", "复制", "订阅提醒", "拨打电话", "拨打", "查看物流", "确认收货", "物流电话", "联系驿站", "联系快递员",
        "分享", "号码保护", "虚拟号码", "已通过虚拟号码发货", "待取件", "物流服务", "物流信息"
    )

    /** 清理标签式地址：剥括号 + 在标点/噪音词处截断，取干净的地址前缀。 */
    private fun cleanAddress(s: String): String {
        var r = stripBrackets(s.trim())
        for (sep in ":：，,。.。;；…") {
            val idx = r.indexOf(sep)
            if (idx >= 0) r = r.substring(0, idx)
        }
        for (noise in ADDR_TRAIL_NOISE) {
            val idx = r.indexOf(noise)
            if (idx >= 0) r = r.substring(0, idx)
        }
        r = dedupeRepeated(stripBrackets(r.trim()))
        return r.trim()
    }

    /** 折叠连续 3 次以上重复的相邻片段（OCR 常把路名/站名读重，如 育新路育新路育新路→育新路）。
     *  只折叠 3+ 次重复，保留合法的双字重复（如站名里正常的两个相同字）。 */
    private fun dedupeRepeated(s: String): String {
        var r = s
        if (r.isEmpty()) return r
        for (len in 4 downTo 2) {
            val re = Regex("(.{$len})\\1{2,}")
            var prev = ""
            while (prev != r) {
                prev = r
                val m = re.find(r) ?: break
                // 用第一个匹配的重复单元长度做逐步折叠（处理同一串内多种重复）
                val unit = m.groupValues[1]
                r = r.replace(Regex(Regex.escape(unit) + "{3,}"), unit)
            }
        }
        return r
    }

    private fun isAddressLike(s: String): Boolean {
        val t = stripBrackets(s)
        if (t.length !in 4..80 || t.none { it in '\u4e00'..'\u9fff' }) return false
        // Must contain address indicators (road/street/building/cabinet etc)
        if (!t.contains(ADDR_PIPE_FULL) && !t.contains(ADDR_LANDMARK)) return false
        // Exclude non-address strings that happen to contain a "号" indicator (e.g. 运单尾号)
        if (listOf("取运单", "运单尾号", "运单", "包裹", "删除").any { t.contains(it) }) return false
        // Exclude pickup-code prefix noise (e.g. OUCR 把「取件码」拆成 件码 紧跟码值，如 件码067865到…)
        if (listOf("件码", "取件码", "取货码", "提取码", "取餐码", "取单码").any { t.contains(it) }) return false
        // Exclude 运单号/单号 标签（如 OCR 误写的 快谨单号）——不是取件地址
        if (t.endsWith("单号") || listOf("运单号", "订单号", "快运单号", "快递单号").any { t.contains(it) }) return false
        // Exclude 隐私号/虚拟号/联系电话 等通知文案（带 **** 脱敏的手机信息），不是取件地址
        if (listOf("号码保护", "虚拟号码", "联系电话", "手机号", "客服电话", "已通过虚拟号码发货").any { t.contains(it) }) return false
        if (t.contains("****")) return false
        return listOf("展开", "复制", "拨打", "导航", "订阅", "延长收货", "查看物流", "确认收货").none { t.contains(it) }
    }

    private data class Candidate(val code: String, val type: CodeType, val score: Float, val source: String)

    private fun posBonus(line: OCREngine.TextLine, screenHeight: Int): Float {
        val box = line.boundingBox ?: return 0f
        if (screenHeight > 0 && box.centerY() in (screenHeight * 0.1f).toInt()..(screenHeight * 0.6f).toInt()) return 5f
        return 0f
    }

    private fun sizeBonus(line: OCREngine.TextLine, avgFontHeight: Float): Float {
        val box = line.boundingBox ?: return 0f
        var b = 0f
        if (box.height() > LARGE_FONT_HEIGHT_PX) b += 10f
        if (avgFontHeight > 0 && box.height() > avgFontHeight * FONT_SIZE_RATIO_THRESHOLD) b += 8f
        return b
    }

    // Order/tracking number patterns (used for brand positioning)
    private val COURIER_ORDER_NUM = Regex("""\b([A-Z]{2,3}\d{8,14}|\d{13,15}|\d{2,4}-\d{3,5}-\d{4,6})\b""")

    private fun sourceFromLine(line: OCREngine.TextLine, hint: String, allLines: List<OCREngine.TextLine>, allText: String): String {
        // Strategy (ordered by reliability):
        // 1. Brand near order/tracking number (courier name usually before order number)
        // 2. Bracket brand validated against known brands
        // 3. Brand+suffix pattern (X快递, X速递, X物流...)
        // 4. Brand in ±3 lines from the code
        // 5. Fallback

        val codeLineIdx = allLines.indexOfFirst { it === line }

        // --- S0: Bracket brand on/nearest the code's own line (before global fallback) ---
        // Handles multi-notification shares where each 【品牌】 precedes its own code on the same block.
        val bracketOnLine: MutableList<String> = mutableListOf()
        if (codeLineIdx >= 0) {
            for (offset in sequenceOf(0, -1, 1, -2, 2)) {
                val t = allLines.getOrNull(codeLineIdx + offset)?.text ?: continue
                BRACKET_BRAND.findAll(t).forEach { bb -> bracketOnLine.add(bb.groupValues[1]) }
            }
        }
        for (content in bracketOnLine) {
            FOOD_BRAND_KEYWORDS.firstOrNull { content.contains(it, ignoreCase = true) }?.let { return it }
            COURIER_BRANDS.firstOrNull { content.contains(it) }?.let { return it }
        }

        // --- S1: Extract brand via order/tracking number positioning ---
        // Courier name typically appears before the tracking number.
        // Find order numbers, then grab the nearest brand before them.
        if (hint in listOf("parcel", "取件码", "取货码")) {
            extractBrandViaOrderNum(allText, allLines)?.let { return it }
        }

        // --- S2: Bracket brand validation ---
        BRACKET_BRAND.find(allText)?.let { m ->
            val content = m.groupValues[1].trim()
            FOOD_BRAND_KEYWORDS.firstOrNull { content.contains(it, ignoreCase = true) }
                ?.let { return it }
            COURIER_BRANDS.firstOrNull { content.contains(it) }
                ?.let { return it }
        }

        // --- S3: Brand+suffix at line level ---
        val brands = if (hint in listOf("food", "取餐码", "取餐号")) FOOD_BRAND_KEYWORDS else COURIER_BRANDS
        val ignoreCase = hint in listOf("food", "取餐码", "取餐号")
        val suffixes = if (hint in listOf("food", "取餐码", "取餐号"))
            listOf("取餐", "外卖", "咖啡", "茶饮", "奶茶", "饮品", "点单", "鲜果", "门店")
        else
            listOf("快递", "速递", "物流", "速运", "超市", "驿站", "智能柜")

        fun brandWithSuffix(text: String): String? {
            for (brand in brands.sortedByDescending { it.length }) {
                val escaped = Regex.escape(brand)
                val suffixPat = suffixes.map { Regex.escape(it) }.joinToString("|")
                if (Regex("$escaped(?:$suffixPat)", if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()).containsMatchIn(text))
                    return brand
            }
            return null
        }

        // S3a: Current line
        brandWithSuffix(line.text)?.let { return it }

        // S3b: Nearby lines (±3)
        if (codeLineIdx >= 0) {
            for (offset in sequenceOf(-1, 1, -2, 2, -3, 3)) {
                allLines.getOrNull(codeLineIdx + offset)?.let { neighbor ->
                    brandWithSuffix(neighbor.text)?.let { return it }
                }
            }
        }

        // --- S4: Global brand+suffix fallback ---
        brandWithSuffix(allText)?.let { return it }

        // --- S5: Proximity-based brand mention (any occurrence near code) ---
        if (codeLineIdx >= 0) {
            val contextLines = allLines.slice(maxOf(0, codeLineIdx - 3)..minOf(allLines.lastIndex, codeLineIdx + 3))
            val contextText = contextLines.joinToString(" ") { it.text }
            for (brand in brands.sortedByDescending { it.length }) {
                if (contextText.contains(brand, ignoreCase))
                    return brand
            }
        }

        return if (hint in listOf("food", "取餐码", "取餐号")) "餐饮" else "快递"
    }

    /** Extract courier brand by looking at text before order/tracking numbers,
     *  with order-number prefix as a stronger signal than OCR text matching. */
    private fun extractBrandViaOrderNum(allText: String, allLines: List<OCREngine.TextLine>): String? {
        val orderMatch = COURIER_ORDER_NUM.find(allText) ?: return null
        val orderNum = orderMatch.value
        val orderStart = orderMatch.range.first
        val textBefore = allText.substring(0, orderStart)

        // S1a: Order number prefix → brand mapping (most reliable, machine-generated)
        val prefixBrand = orderNumPrefixToBrand(orderNum)

        // S1b: Find the last brand mention before the order number in OCR text
        var textBrand: String? = null
        var bestPos = -1
        for (brand in COURIER_BRANDS.sortedByDescending { it.length }) {
            val idx = textBefore.lastIndexOf(brand)
            if (idx > bestPos) {
                bestPos = idx
                textBrand = brand
            }
        }

        // Prefix wins over text when they conflict (prefix is machine-generated, more reliable)
        return prefixBrand ?: textBrand
    }

    /** Map order/tracking number prefix to courier brand.
     *  参考开源社区（GitHub shallowlong/courier-rules, zhili007/kuaidi-tracker）。
     *  只对“字母前缀保留字”做可靠识别（字母段几乎无重叠、零误判）；
     *  纯数字段各家重叠严重（顺丰/中通/申通/韵达/EMS 都订阅了部分 12/13 位段），
     *  不再派到此处，交由 extractBrandViaOrderNum 的 textBrand 依据 OCR 文本中的品牌名判定。 */
    private fun orderNumPrefixToBrand(num: String): String? {
        val u = num.trim().uppercase()
        return when {
            REG_JP.matches(u) || REG_JT.matches(u) -> "极兔"
            REG_JD.matches(u) || REG_JD_VA.matches(u) -> "京东物流"
            REG_SF.matches(u) -> "顺丰"
            REG_YT.matches(u) || REG_YTO.matches(u) -> "圆通"
            REG_YD.matches(u) || REG_YUNDA.matches(u) -> "韵达"
            REG_ZTO.matches(u) -> "中通"
            REG_ST.matches(u) || REG_STO.matches(u) -> "申通"
            REG_EMS.matches(u) -> "EMS"
            REG_POSTAL.matches(u) -> "邮政"
            REG_DPK.matches(u) || REG_DPL.matches(u) -> "德邦"
            else -> null
        }
    }

    /** 公开：根据运单号前缀识别快递品牌（中文名）。供验证器等模块复用，避免规则重复。 */
    fun guessOrderBrand(num: String): String? = orderNumPrefixToBrand(num)

    /**
     * 公开：从 OCR 全文提取第一个运单/快递单号。
     * 供快递100验证器等模块反向查询取件码/地址使用。
     */
    fun findOrderNumber(allText: String): String? =
        COURIER_ORDER_NUM.find(allText)?.value

    /**
     * 公开：校验字符串是否为合法取餐/取件码格式（复用全部已知规则）。
     * 供 AI 提取结果过滤噪声（AI 不比正则可靠，需格式白名单把关）。
     */
    fun isValidPickupCode(code: String): Boolean {
        val c = code.trim()
        if (c.length !in 1..14) return false
        return VALID_CODE_FORMATS.any { it.matches(c) }
    }

    // 合法取件/取餐码格式白名单（与上方解析正则一一对应，去锚点/去分组后用于全串匹配）
    private val VALID_CODE_FORMATS = listOf(
        Regex("[A-Za-z]?\\d{1,2}-\\d{1,2}-\\d{1,2}-\\d{2,4}"), // FOUR_SEGMENT
        Regex("\\d{1,3}-\\d{1,2}-\\d{3,6}"),                       // THREE_SEGMENT
        Regex("[A-Z]-\\d{1,2}-\\d{3,4}", RegexOption.IGNORE_CASE),   // LETTER_TWO_SEGMENT
        Regex("[A-Za-z]-?\\d{5,6}"),                                  // LETTER_DASH_FIVE
        Regex("[A-Za-z]\\d{1,2}-\\d{1,2}-\\d{3,6}", RegexOption.IGNORE_CASE), // LETTER_THREE_SEG
        Regex("[A-Za-z]-\\d{3,4}", RegexOption.IGNORE_CASE),          // LETTER_DASH_THREE
        Regex("\\d{6,8}"),                                            // LONG_NUMBER
        Regex("[A-Z]\\s*-?\\s*\\d{2,4}", RegexOption.IGNORE_CASE),  // LETTER_NUMBER_FOOD
        Regex("\\d{2,5}")                                              // PURE_NUMBER_FOOD
    )

    private fun isExcluded(code: String, context: Context? = null) =
        EXCLUDE_PATTERNS.any { it.containsMatchIn(code) } ||
        // A3: 用户标记"不是取件码"的可学习排除片段
        com.pickupcode.app.learner.PatternLearner.isLearnedExcluded(code, context)

    // ---------------------------------------------------------------
    // Pattern learning feedback
    // ---------------------------------------------------------------

    private val formatPatterns = linkedMapOf(
        "FOUR_SEGMENT_PARCEL" to FOUR_SEGMENT_PARCEL,
        "THREE_SEGMENT_PARCEL" to THREE_SEGMENT_PARCEL,
        "LETTER_TWO_SEGMENT_PARCEL" to LETTER_TWO_SEGMENT_PARCEL,
        "LETTER_DASH_FIVE_PARCEL" to LETTER_DASH_FIVE_PARCEL,
        "LONG_NUMBER_PARCEL" to LONG_NUMBER_PARCEL,
    )

    private fun recordLearning(context: Context, results: List<ExtractedCode>, allText: String, source: String) {
        if (results.isNotEmpty()) {
            for (r in results) {
                val pid = classifyFormat(r.code)
                PatternLearner.recordAttempt(context, pid)
            }
        } else {
            PatternLearner.recordMiss(context, allText.take(500), source)
        }
    }

    private fun classifyFormat(code: String): String {
        for ((id, regex) in formatPatterns) {
            if (regex.matches(code)) return id
        }
        return PATTERN_PREFIXED
    }

    fun getPatternId(code: String): String = classifyFormat(code)
}