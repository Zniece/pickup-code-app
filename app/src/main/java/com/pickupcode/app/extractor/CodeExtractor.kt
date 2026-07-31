package com.pickupcode.app.extractor

import android.graphics.Rect
import android.content.Context
import com.pickupcode.app.learner.PatternLearner
import com.pickupcode.app.ocr.OCREngine

object CodeExtractor {

    data class ExtractedCode(val code: String, val type: CodeType, val source: String, val confidence: Float)
    enum class CodeType { pickup_food, pickup_parcel }
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
    private val LETTER_DASH_THREE_PARCEL = Regex("\\b([A-Za-z])-(\\d{3,4})\\b", RegexOption.IGNORE_CASE)
    private val LONG_NUMBER_PARCEL = Regex("\\b(\\d{6,8})\\b")
    private val LETTER_NUMBER_FOOD = Regex("\\b([A-Z]\\s*[-]?\\s*\\d{2,4})\\b", RegexOption.IGNORE_CASE)
    private val PURE_NUMBER_FOOD = Regex("\\b(?<![\\d-])(\\d{2,5})(?![\\d])\\b")
    private val PREFIXED_CODE = Regex("(取[餐件货单]码|取餐号|取单号|排号|券号|提取码)[:：]?\\s*([A-Za-z0-9\\-]{2,12})")

    private const val SCORE_PREFIXED = 100f; private const val SCORE_THREE_SEG = 95f
    private const val SCORE_FOUR_SEG = 95f
    private const val SCORE_LETTER_TWO_SEG = 85f
    private const val SCORE_LETTER_DASH_FIVE = 85f
    private const val SCORE_LETTER_DASH_THREE = 80f
    private const val SCORE_LETTER_NUM_FOOD = 80f
    private const val SCORE_PURE_NUM_FOOD = 75f; private const val SCORE_LONG_NUM_PARCEL = 60f
    private const val PATTERN_PREFIXED = "PREFIXED_CODE"
    private const val LARGE_FONT_HEIGHT_PX = 60; private const val FONT_SIZE_RATIO_THRESHOLD = 1.5f

    private val BRACKET_BRAND = Regex("【([^】]+)】")

    private val FOOD_BRAND_KEYWORDS = listOf(
        "瑞幸", "luckin", "星巴克", "starbucks", "麦当劳", "mcdonald",
        "肯德基", "kfc", "喜茶", "heytea", "奈雪", "蜜雪冰城",
        "霸王茶姬", "茶百道", "一点点", "coco", "书亦", "古茗",
        "茶颜悦色", "沪上阿姨", "甜啦啦", "益禾堂", "林里", "LINLEE"
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
        Regex("\\b\\d{4}-\\d{1,2}\\b"), // date suffix like 1124-15
        Regex("\\b\\d{6,8}-\\d{5,}\\b") // full order number
    )

    // ---------------------------------------------------------------
    // Code extraction
    // ---------------------------------------------------------------

    fun extract(lines: List<OCREngine.TextLine>, screenHeight: Int = 0, context: Context? = null): List<ExtractedCode> {
        val candidates = mutableListOf<Candidate>()
        val allText = lines.joinToString(" ") { it.text }
        val isFoodContext = FOOD_KEYWORDS.any { allText.contains(it, ignoreCase = true) }
        val isParcelContext = PARCEL_KEYWORDS.any { allText.contains(it) }
        val avgFontHeight = lines.mapNotNull { it.boundingBox?.height()?.toFloat() }
            .takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f

        for (line in lines) {
            PREFIXED_CODE.find(line.text)?.let { m ->
                val code = m.groupValues[2]
                if (!isExcluded(code)) {
                    val p = m.groupValues[1]
                    candidates.add(Candidate(code,
                        if (p.contains("餐") || p.contains("单")) CodeType.pickup_food else CodeType.pickup_parcel,
                        SCORE_PREFIXED, sourceFromLine(line, p, lines, allText)))
                }
            }
        }

        val prefixKw = listOf("取餐码", "取餐号", "取单码", "取单号", "取件码", "取货码", "排号", "提取码")
        for (i in lines.indices) {
            if (prefixKw.any { lines[i].text.contains(it, ignoreCase = true) } && i + 1 < lines.size) {
                val nextLine = lines[i + 1].text.trim()
                // Match pure numbers or letter-dash-number codes on the next line
                val nextMatch = Regex("^\\s*([A-Za-z0-9\\-]{2,12})\\s*$").find(nextLine)
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
                        val ctxBonus: Float = 0f, val sizeBonus: Boolean = false, val pureNum: Boolean = false)
        val rules = mutableListOf(
            Rule(THREE_SEGMENT_PARCEL, CodeType.pickup_parcel, SCORE_THREE_SEG),
            Rule(FOUR_SEGMENT_PARCEL, CodeType.pickup_parcel, SCORE_FOUR_SEG),
            Rule(LETTER_TWO_SEGMENT_PARCEL, CodeType.pickup_parcel, SCORE_LETTER_TWO_SEG),
            Rule(LETTER_DASH_FIVE_PARCEL, CodeType.pickup_parcel, SCORE_LETTER_DASH_FIVE),
            Rule(LETTER_DASH_THREE_PARCEL, CodeType.pickup_parcel, SCORE_LETTER_DASH_THREE),
            Rule(LONG_NUMBER_PARCEL, CodeType.pickup_parcel, SCORE_LONG_NUM_PARCEL, 10f),
            Rule(LETTER_NUMBER_FOOD, CodeType.pickup_food, SCORE_LETTER_NUM_FOOD, 10f, true),
            Rule(PURE_NUMBER_FOOD, CodeType.pickup_food, SCORE_PURE_NUM_FOOD, 10f, true, true)
        )

        // Load auto-learned patterns
        if (context != null) {
            val learned = com.pickupcode.app.learner.PatternLearner.getLearnedPatterns(context)
            for (rule in learned) {
                try {
                    val regex = Regex(rule.regex)
                    val type = if (rule.type == "pickup_food") CodeType.pickup_food else CodeType.pickup_parcel
                    // Auto-learned patterns get lower base score but benefit from context bonus
                    rules.add(Rule(regex, type, 65f, 10f))
                } catch (_: Exception) { /* skip invalid regex */ }
            }
        }

        for (line in lines) {
            val pos = posBonus(line, screenHeight)
            val size = sizeBonus(line, avgFontHeight)
            for (rule in rules) {
                rule.regex.findAll(line.text).forEach matchLoop@{ m ->
                    if (isExcluded(m.value)) return@matchLoop
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

                    val ctxOk = when (rule.type) { CodeType.pickup_food -> isFoodContext; CodeType.pickup_parcel -> isParcelContext }
                    if (ctxOk) s += rule.ctxBonus
                    val conflict = when (rule.type) { CodeType.pickup_food -> isParcelContext && !isFoodContext; CodeType.pickup_parcel -> isFoodContext && !isParcelContext }
                    if (conflict) s -= 8f

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
        val hasOrder = allText.contains(Regex("\\b\\d{6,}-\\d{5,}\\b")) || allText.contains(Regex("\\b\\d{2,4}-\\d{3,4}-\\d{4,}\\b"))
        if (hasOrder) {
            candidates.replaceAll { c ->
                if (LONG_NUMBER_PARCEL.matches(c.code)) c.copy(score = c.score - 50f)
                else if (c.type == CodeType.pickup_parcel && c.code.all { it.isDigit() }) c.copy(score = c.score - 30f)
                else c
            }
        }

        val codeKeywordLines = lines.filter { it.text.contains(Regex("(取[件餐货]码|取餐号)")) }
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
        val seen = mutableSetOf<String>()
        val results = mutableListOf<ExtractedCode>()
        val top = candidates.firstOrNull()?.score ?: 0f
        for (c in candidates) {
            if (c.code in seen) continue; seen.add(c.code)
            if (c.score >= top * 0.75f)
                results.add(ExtractedCode(c.code, c.type, c.source, (c.score / SCORE_PREFIXED).coerceIn(0f, 1f)))
        }
        if (context != null) recordLearning(context, results, allText)
        return results
    }

    // ---------------------------------------------------------------
    // Address extraction (structured)
    // ---------------------------------------------------------------

    fun extractLocation(lines: List<OCREngine.TextLine>, allText: String): PickupLocation {
        var stationName = ""
        var fullAddress = ""
        var cabinet: String? = null

        // S1: 【】 bracket brand for station name
        BRACKET_BRAND.find(allText)?.let { m ->
            stationName = m.groupValues[1].trim()
        }

        // S2: pipe-separated "shop | address"
        for (line in lines) {
            for (sep in listOf('|', '｜')) {
                val bar = line.text.indexOf(sep)
                if (bar < 0) continue
                val left = line.text.substring(0, bar).trim()
                val right = line.text.substring(bar + 1).trim()
                if (stationName.isEmpty() && isAddressLike(left).not() && left.isNotBlank()) {
                    stationName = extractStationName(left)
                }
                if (fullAddress.isEmpty() && isAddressLike(right)) fullAddress = right.take(80)
            }
        }

        // S3: explicit label
        val explicit = listOf("取件点位置", "取件地址", "收货地址", "代收点地址", "取件点")
        for (line in lines) for (p in explicit) {
            val i = line.text.indexOf(p)
            if (i < 0) continue
            var a = line.text.substring(i + p.length).trimStart(':', '：', ' ')
            for (sep in listOf('|', '｜')) {
                val bar = a.indexOf(sep)
                if (bar >= 0) { if (stationName.isEmpty()) stationName = extractStationName(a.substring(0, bar).trim()); a = a.substring(bar + 1).trim() }
            }
            if (fullAddress.isEmpty() && isAddressLike(a)) fullAddress = a.take(80)
        }

        // S4: address label
        if (fullAddress.isEmpty()) {
            Regex("地址[:：]\\s*(.+)").find(allText)?.let { m ->
                val a = m.groupValues[1].trim()
                if (isAddressLike(a)) fullAddress = a.take(80)
            }
        }

        // S5: "已放至/已暂存至" pattern
        if (fullAddress.isEmpty()) {
            Regex("(?:已放至|已暂存至|已放入|送达)\\s*([^，,。.\\n]{4,80})").find(allText)?.let { m ->
                val a = m.groupValues[1].trim()
                if (isAddressLike(a)) fullAddress = a.take(80)
            }
        }

        // S6: "到...取件/领取/门店" template (SMS style)
        if (fullAddress.isEmpty()) {
            Regex("到(.+?)(领取|取件|门店)").find(allText)?.let { m ->
                val a = m.groupValues[1].trim()
                if (isAddressLike(a)) fullAddress = a.take(80)
                if (stationName.isEmpty() && a.isNotBlank()) stationName = extractStationName(a)
            }
        }

        // S7: cabinet number + address from "号柜" line
        for (line in lines) {
            if (!line.text.contains("号柜")) continue
            Regex("(\\d+)号柜").find(line.text)?.let { cabinet = it.groupValues[1] }
            if (fullAddress.isEmpty() && isAddressLike(line.text.trim()))
                fullAddress = line.text.trim().take(80)
        }

        // S8: nearby lines after prefix keywords
        val prefixes = explicit + listOf("代收点", "地址", "号柜")
        for (i in lines.indices) {
            if (!prefixes.any { lines[i].text.contains(it) }) continue
            if (stationName.isEmpty()) stationName = extractStationName(lines[i].text)
            for (j in i + 1..minOf(i + 2, lines.lastIndex)) {
                val n = lines[j].text.trim()
                if (fullAddress.isEmpty() && isAddressLike(n)) { fullAddress = n.take(80); break }
            }
        }

        // S9: parenthesized address
        if (fullAddress.isEmpty()) {
            for (line in lines) {
                val m = Regex("（([^）]*[路街段柜]）").find(line.text)
                if (m != null && isAddressLike(m.groupValues[1])) {
                    fullAddress = m.groupValues[1].take(80); break
                }
            }
        }

        // S10: fallback - any line with road/street/cabinet indicators
        if (fullAddress.isEmpty()) {
            for (line in lines) {
                if (stationName.isEmpty()) stationName = extractStationName(line.text)
                if (line.text.contains(Regex("[路街段柜]")) && isAddressLike(line.text.trim())) {
                    fullAddress = line.text.trim().take(80); break
                }
            }
        }

        // Determine station type
        val stype = classifyStation(stationName, fullAddress, allText)

        // If station name still empty, try to extract from full address or all text
        if (stationName.isEmpty()) {
            stationName = extractStationName(fullAddress)
        }
        if (stationName.isEmpty()) {
            stationName = extractStationName(allText)
        }

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
        BRACKET_BRAND.find(text)?.let { return it.groupValues[1].trim() }
        // Try known station keywords
        for (kw in STATION_TYPE_MAP.keys) {
            if (text.contains(kw)) {
                // Extract the full station name: text before and including the keyword
                val idx = text.indexOf(kw)
                val start = (0 until idx).lastOrNull { text[it] in "，,。.；;、 " }?.plus(1) ?: 0
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

    private fun isAddressLike(s: String): Boolean {
        if (s.length !in 4..80 || s.none { it in '\u4e00'..'\u9fff' }) return false
        // Must contain address indicators (road/street/building/cabinet etc)
        if (!s.contains(Regex("[路街巷弄号栋幢单元柜室楼区县镇乡村庄]"))) return false
        return listOf("展开", "复制", "拨打", "导航", "订阅", "延长收货", "查看物流", "确认收货").none { s.contains(it) }
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
            listOf("取餐", "外卖", "咖啡", "茶饮", "奶茶")
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

    /** Map order/tracking number prefix to courier brand. */
    private fun orderNumPrefixToBrand(num: String): String? = when {
        num.startsWith("JT") || num.startsWith("jt") -> "极兔"
        num.startsWith("SF") -> "顺丰"
        num.startsWith("YT") -> "圆通"
        num.startsWith("JD") -> "京东快递"
        num.startsWith("77") -> "申通"
        num.startsWith("99") -> "中通"
        num.startsWith("88") -> "韵达"
        else -> null
    }

    private fun isExcluded(code: String) = EXCLUDE_PATTERNS.any { it.containsMatchIn(code) }

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

    private fun recordLearning(context: Context, results: List<ExtractedCode>, allText: String) {
        if (results.isNotEmpty()) {
            for (r in results) {
                val pid = classifyFormat(r.code)
                PatternLearner.recordAttempt(context, pid)
            }
        } else {
            PatternLearner.recordMiss(context, allText.take(500))
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