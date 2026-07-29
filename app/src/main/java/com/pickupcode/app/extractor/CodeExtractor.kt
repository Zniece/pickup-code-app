package com.pickupcode.app.extractor

import android.graphics.Rect
import com.pickupcode.app.ocr.OCREngine

/**
 * 取餐码/取件码提取器 — 基于正则 + 位置加权的策略
 *
 * 评分策略：
 * 1. 用正则匹配候选码
 * 2. 按字号/位置/上下文关键词加权打分
 *    - 中文前缀匹配（"取餐码: A-356"）→ 100分（最高优先级）
 *    - 三段式取件码（10-2-7507）→ 90分 + 位置加分
 *    - 字母-两段式（A-26-001）→ 85分 + 位置加分
 *    - 字母+数字取餐码（A-356）→ 80分 + 位置 + 字号加分
 *    - 纯数字取餐码（2-4位）→ 75分 + 位置 + 字号加分（仅餐饮语境）
 *    - 纯数字长码取件（6-8位）→ 70分 + 位置加分（仅快递语境）
 *    - 无语境兜底三段式 → 60分
 * 3. 位置加分：boundingBox 在屏幕中上部 + 居中 → +5~15分
 * 4. 字号加分：大号字体（height > 60px）→ +10分
 */
object CodeExtractor {

    data class ExtractedCode(
        val code: String,
        val type: CodeType,
        val source: String,
        val confidence: Float
    )

    enum class CodeType { pickup_food, pickup_parcel }

    // === 正则模式 ===

    // 取餐码 — 纯数字序号（瑞幸、麦当劳、蜜雪冰城等）
    // 支持 2-5 位：10006（林里）、356（肯德基）、1234（瑞幸）
    private val PURE_NUMBER_FOOD = Regex("\\b(?<![\\d-])(\\d{2,5})(?![\\d])\\b")

    // 取餐码 — 字母+数字（肯德基 A-356 等）
    private val LETTER_NUMBER_FOOD = Regex("\\b([A-Z]\\s*[-]?\\s*\\d{2,4})\\b", RegexOption.IGNORE_CASE)

    // 取件码 — 三段式（菜鸟 10-2-7507、妈妈驿站 2-2-1521）
    private val THREE_SEGMENT_PARCEL = Regex("\\b(\\d{1,3})-(\\d{1,2})-(\\d{3,6})\\b")

    // 取件码 — 字母+两段式（兔喜 A-26-001）
    private val LETTER_TWO_SEGMENT_PARCEL = Regex("\\b([A-Z])-(\\d{1,2})-(\\d{3,4})\\b", RegexOption.IGNORE_CASE)

    // 取件码 — 纯数字长码（丰巢、京东快递柜）
    private val LONG_NUMBER_PARCEL = Regex("\\b(\\d{6,8})\\b")

    // 带前缀中文的格式（兜底）— 最高优先级
    // 支持：取餐码、取餐号、取单码、取件码、取货码、排号、券号、提取码
    private val PREFIXED_CODE = Regex("(取[餐件货单]码|取餐号|取单号|排号|券号|提取码)[:：]?\\s*([A-Za-z0-9\\-]{2,12})")

    // === 评分常量 ===
    private const val SCORE_PREFIXED = 100f
    private const val SCORE_THREE_SEG = 95f
    private const val SCORE_LETTER_TWO_SEG = 85f
    private const val SCORE_LETTER_NUM_FOOD = 80f
    private const val SCORE_PURE_NUM_FOOD = 75f
    private const val SCORE_LONG_NUM_PARCEL = 60f

    private const val LARGE_FONT_HEIGHT_PX = 60
    // 取餐码相对字号：字号超过平均值多少倍才算大
    private const val FONT_SIZE_RATIO_THRESHOLD = 1.5f

    // 品牌名列表（用于来源标注）
    private val FOOD_BRAND_KEYWORDS = listOf(
        "瑞幸", "luckin", "星巴克", "starbucks", "麦当劳", "mcdonald",
        "肯德基", "kfc", "喜茶", "heytea", "奈雪", "蜜雪冰城",
        "霸王茶姬", "茶百道", "一点点", "coco", "书亦", "古茗",
        "茶颜悦色", "沪上阿姨", "甜啦啦", "益禾堂",
        "林里", "LINLEE"
    )

    // 语境关键词（品牌名 + 通用取餐词，用于判断是否餐饮页面）
    private val FOOD_KEYWORDS = FOOD_BRAND_KEYWORDS + listOf(
        "取餐", "取餐码", "取餐号", "取单码", "取单号",
        "请取餐", "正在制作", "等待取餐"
    )

    private val PARCEL_KEYWORDS = listOf(
        "菜鸟", "驿站", "丰巢", "妈妈驿站", "兔喜", "快递超市",
        "京东快递", "顺丰", "中通", "圆通", "申通", "韵达", "极兔",
        "取件码", "取货码", "提取码", "快递柜", "货架"
    )

    private val EXCLUDE_PATTERNS = listOf(
        Regex("\\b1[3-9]\\d{9}\\b"),       // 手机号
        Regex("\\b0\\d{2,3}-?\\d{7,8}\\b"), // 固话
        Regex("\\d{1,2}:\\d{2}"),              // 时间
        Regex("[￥¥\$]\\s*\\d+"),               // 金额符号+数字
        Regex("\\d+\\.?\\d*\\s*[元块]"),         // 金额后缀（25元、12.5块）
        Regex("\\b\\d{12,}\\b"),               // 订单号(长数字)
        Regex("\\b\\d{4}年\\d{1,2}月\\b"),    // 日期
        Regex("\\d+\\s*[个份件杯张条]"),         // 数量单位（2份、3件、1杯）
        Regex("\\d+\\s*[分钟小时]"),             // 时长（30分钟、1小时）
        Regex("\\d+\\s*[号桌台]"),               // 桌台号（12号桌、5号台）
        Regex("\\d+\\s*[号楼层室]"),             // 楼层/房间号
        Regex("\\d+\\s*[折]"),                   // 折扣（8折）
        Regex("\\d+\\s*[分](?![钟])"),           // 评分/积分（4.5分、100分）
        Regex("\\d+\\s*[毫厘克千克升毫升]"),     // 度量衡
    )

    fun extract(lines: List<OCREngine.TextLine>, screenHeight: Int = 0): List<ExtractedCode> {
        val candidates = mutableListOf<Candidate>()

        val allText = lines.joinToString(" ") { it.text }
        val isFoodContext = FOOD_KEYWORDS.any { allText.contains(it, ignoreCase = true) }
        val isParcelContext = PARCEL_KEYWORDS.any { allText.contains(it) }

        // 计算所有行的平均字号，用于相对字号判断
        val avgFontHeight = lines.mapNotNull { it.boundingBox?.height()?.toFloat() }
            .takeIf { it.isNotEmpty() }
            ?.average()?.toFloat() ?: 0f

        // 1. 中文前缀匹配 — 按行遍历，确保品牌匹配到正确的行
        for (line in lines) {
            PREFIXED_CODE.find(line.text)?.let { match ->
                val code = match.groupValues[2]
                if (!isExcluded(code)) {
                    val prefix = match.groupValues[1]
                    val type = if (prefix.contains("取餐") || prefix.contains("餐") || prefix.contains("取单"))
                        CodeType.pickup_food else CodeType.pickup_parcel
                    val source = extractSourceFromLine(line, prefix, lines, allText)
                    candidates.add(Candidate(code, type, SCORE_PREFIXED, source))
                }
            }
        }

        // 1b. 跨行前缀匹配：如果某行是"取餐码/取单码"等关键词，下一行是纯数字，也视为前缀匹配
        val prefixKeywords = listOf("取餐码", "取餐号", "取单码", "取单号", "取件码", "取货码", "排号", "提取码")
        for (i in lines.indices) {
            val line = lines[i]
            val isPrefixLine = prefixKeywords.any { line.text.contains(it, ignoreCase = true) }
            if (isPrefixLine && i + 1 < lines.size) {
                val nextLine = lines[i + 1]
                // 下一行是纯数字（2-5位），且不是排除项
                val nextMatch = Regex("^\\s*(\\d{2,5})\\s*$").find(nextLine.text)
                if (nextMatch != null && !isExcluded(nextMatch.value)) {
                    val type = if (line.text.contains("餐") || line.text.contains("单"))
                        CodeType.pickup_food else CodeType.pickup_parcel
                    val source = extractSourceFromLine(line, "取餐码", lines, allText)
                    candidates.add(Candidate(nextMatch.value, type, SCORE_PREFIXED, source))
                }
            }
        }

        // 2 & 3. 单行匹配 — 统一规则表驱动
        // 所有正则全部运行，上下文只影响加分，不再阻塞匹配
        data class MatchRule(
            val regex: Regex,
            val type: CodeType,
            val baseScore: Float,
            val sourceHint: String,
            val contextBonus: Float = 0f,  // 上下文匹配额外加分
            val needsSizeBonus: Boolean = false,
            val isPureNumber: Boolean = false  // 是否为纯数字码（需要额外严格判断）
        )

        val rules = listOf(
            MatchRule(THREE_SEGMENT_PARCEL, CodeType.pickup_parcel, SCORE_THREE_SEG, "parcel"),
            MatchRule(LETTER_TWO_SEGMENT_PARCEL, CodeType.pickup_parcel, SCORE_LETTER_TWO_SEG, "parcel"),
            MatchRule(LONG_NUMBER_PARCEL, CodeType.pickup_parcel, SCORE_LONG_NUM_PARCEL, "parcel", contextBonus = 10f),
            MatchRule(LETTER_NUMBER_FOOD, CodeType.pickup_food, SCORE_LETTER_NUM_FOOD, "food", contextBonus = 10f, needsSizeBonus = true),
            MatchRule(PURE_NUMBER_FOOD, CodeType.pickup_food, SCORE_PURE_NUM_FOOD, "food", contextBonus = 10f, needsSizeBonus = true, isPureNumber = true),
        )

        for (line in lines) {
            val posBonus = positionBonus(line, screenHeight)
            val sizeBonus = fontSizeBonus(line, avgFontHeight)

            for (rule in rules) {
                rule.regex.findAll(line.text).forEach matchLoop@{ match ->
                    if (!isExcluded(match.value)) {
                        var totalScore = rule.baseScore + posBonus
                        if (rule.needsSizeBonus) totalScore += sizeBonus

                        // 纯数字取餐码：需要更强的特征才能避免误判
                        if (rule.isPureNumber) {
                            val digitCount = match.value.length
                            val hasFoodKeywordInLine = FOOD_KEYWORDS.any { line.text.contains(it, ignoreCase = true) }
                            val isRelativelyLarge = avgFontHeight > 0 && line.boundingBox != null &&
                                line.boundingBox.height() > avgFontHeight * FONT_SIZE_RATIO_THRESHOLD

                            // 2位纯数字几乎一定是价格/数量/时间，除非有取餐关键词或大字号
                            if (digitCount <= 2 && !hasFoodKeywordInLine && !isRelativelyLarge) {
                                return@matchLoop
                            }

                            // 5位数字比2-4位更可能是取餐码（价格/时间通常是2-4位）
                            if (digitCount == 5) {
                                totalScore += 15f
                            }

                            if (isFoodContext) {
                                // 餐饮上下文：行内有取餐关键词 或 字号明显大于平均 → 通过
                                if (hasFoodKeywordInLine || isRelativelyLarge) {
                                    totalScore += 10f
                                } else if (line.boundingBox != null && line.boundingBox.height() > LARGE_FONT_HEIGHT_PX) {
                                    // 绝对大字号也接受
                                    totalScore += 5f
                                } else {
                                    // 餐饮页面但该行无关键词、字号也不大 → 可能是价格/数量，降权
                                    totalScore -= 35f
                                }
                            } else {
                                // 无餐饮上下文：必须有取餐关键词 或 明显大字号才考虑
                                if (!hasFoodKeywordInLine && !isRelativelyLarge) {
                                    return@matchLoop
                                }
                            }
                        }

                        // 上下文匹配加分
                        val ctxOk = when (rule.type) {
                            CodeType.pickup_food -> isFoodContext
                            CodeType.pickup_parcel -> isParcelContext
                        }
                        if (ctxOk) totalScore += rule.contextBonus
                        // 上下文冲突时轻度降分
                        val ctxConflict = when (rule.type) {
                            CodeType.pickup_food -> isParcelContext && !isFoodContext
                            CodeType.pickup_parcel -> isFoodContext && !isParcelContext
                        }
                        if (ctxConflict) totalScore -= 8f

                        candidates.add(Candidate(
                            match.value, rule.type,
                            totalScore,
                            extractSourceFromLine(line, rule.sourceHint, lines, allText)
                        ))
                    }
                }
            }
        }

        if (candidates.isEmpty()) return emptyList()

        // 全局上下文修正
        // 仅单一场景（纯快递 or 纯餐饮）时抑制对立类型，混合场景不惩罚任何一方
        if (isParcelContext && !isFoodContext) {
            candidates.replaceAll { c ->
                if (c.type == CodeType.pickup_food) c.copy(score = c.score - 50f) else c
            }
        }
        if (isFoodContext && !isParcelContext) {
            candidates.replaceAll { c ->
                if (c.type == CodeType.pickup_parcel) c.copy(score = c.score - 50f) else c
            }
        }
        // 3. 如果存在三段式取件码（最高可靠性），长数字大概率是订单号，降权
        val hasThreeSegment = candidates.any {
            it.type == CodeType.pickup_parcel && THREE_SEGMENT_PARCEL.matches(it.code)
        }
        // 4. 检测文本中是否有订单号格式（多段长数字用横杠连接）
        val hasOrderNumber = allText.contains(Regex("\\b\\d{6,}-\\d{5,}\\b")) ||
            allText.contains(Regex("\\b\\d{2}-\\d{5,}-\\d{4,}\\b"))

        if (hasThreeSegment) {
            candidates.replaceAll { c ->
                if (c.type == CodeType.pickup_parcel && LONG_NUMBER_PARCEL.matches(c.code)) {
                    c.copy(score = c.score - 30f)
                } else c
            }
        }
        if (hasOrderNumber) {
            candidates.replaceAll { c ->
                if (LONG_NUMBER_PARCEL.matches(c.code)) {
                    c.copy(score = c.score - 20f)
                } else c
            }
        }

        candidates.sortByDescending { it.score }

        // 去重：相同 code 保留分数最高的
        val seen = mutableSetOf<String>()
        val results = mutableListOf<ExtractedCode>()
        val topScore = candidates.firstOrNull()?.score ?: 0f

        for (c in candidates) {
            if (c.code in seen) continue
            seen.add(c.code)
            // 只返回得分 ≥ 最高分 75% 的候选，屏蔽噪声
            if (c.score >= topScore * 0.75f) {
                results.add(ExtractedCode(c.code, c.type, c.source, (c.score / SCORE_PREFIXED).coerceIn(0f, 1f)))
            }
        }
        return results
    }

    private data class Candidate(
        val code: String,
        val type: CodeType,
        val score: Float,
        val source: String
    )

    private fun positionBonus(line: OCREngine.TextLine, screenHeight: Int): Float {
        val box = line.boundingBox ?: return 0f
        var bonus = 0f

        if (screenHeight > 0) {
            val verticalCenter = box.centerY()
            if (verticalCenter in (screenHeight * 0.1f).toInt()..(screenHeight * 0.6f).toInt()) {
                bonus += 5f
            }
        }

        if (box.left > 0 && box.right > 0) {
            bonus += 3f
        }

        return bonus
    }

    private fun fontSizeBonus(line: OCREngine.TextLine, avgFontHeight: Float = 0f): Float {
        val box = line.boundingBox ?: return 0f
        val height = box.height().toFloat()
        var bonus = 0f

        // 绝对大字号
        if (height > LARGE_FONT_HEIGHT_PX) bonus += 10f

        // 相对字号：超过平均值1.5倍 → 额外加分
        if (avgFontHeight > 0 && height > avgFontHeight * FONT_SIZE_RATIO_THRESHOLD) {
            bonus += 8f
        }

        return bonus
    }

    private fun extractSource(allText: String, hint: String): String {
        return when (hint) {
            "food", "取餐码", "取餐号" ->
                FOOD_BRAND_KEYWORDS.firstOrNull { allText.contains(it, ignoreCase = true) } ?: "餐饮"
            "parcel", "取件码", "取货码" ->
                PARCEL_KEYWORDS.firstOrNull { allText.contains(it) } ?: "快递"
            else -> hint
        }
    }

    /** 按行匹配品牌：找到包含品牌关键词的行，而不是全局第一个 */
    private fun extractSourceFromLine(
        line: OCREngine.TextLine,
        hint: String,
        allLines: List<OCREngine.TextLine> = emptyList(),
        allText: String = ""
    ): String {
        return when (hint) {
            "food", "取餐码", "取餐号" -> {
                // 1. 先在这一行找品牌名
                val lineBrand = FOOD_BRAND_KEYWORDS.firstOrNull { line.text.contains(it, ignoreCase = true) }
                if (lineBrand != null) return lineBrand

                // 2. 前后三行找品牌名（±3 行，通知卡片通常有间距）
                if (allLines.isNotEmpty()) {
                    val lineIndex = allLines.indexOfFirst { it === line }
                    if (lineIndex >= 0) {
                        val nearbyLines = listOfNotNull(
                            allLines.getOrNull(lineIndex - 1),
                            allLines.getOrNull(lineIndex - 2),
                            allLines.getOrNull(lineIndex - 3),
                            allLines.getOrNull(lineIndex + 1),
                            allLines.getOrNull(lineIndex + 2),
                            allLines.getOrNull(lineIndex + 3)
                        )
                        val nearbyBrand = nearbyLines.firstNotNullOfOrNull { nearbyLine ->
                            FOOD_BRAND_KEYWORDS.firstOrNull { nearbyLine.text.contains(it, ignoreCase = true) }
                        }
                        if (nearbyBrand != null) return nearbyBrand
                    }
                }

                // 3. 全局兜底
                FOOD_BRAND_KEYWORDS.firstOrNull { allText.contains(it, ignoreCase = true) } ?: "餐饮"
            }
            "parcel", "取件码", "取货码" -> {
                val lineBrand = PARCEL_KEYWORDS.firstOrNull { line.text.contains(it) }
                if (lineBrand != null) return lineBrand

                if (allLines.isNotEmpty()) {
                    val lineIndex = allLines.indexOfFirst { it === line }
                    if (lineIndex >= 0) {
                        val nearbyLines = listOfNotNull(
                            allLines.getOrNull(lineIndex - 1),
                            allLines.getOrNull(lineIndex - 2),
                            allLines.getOrNull(lineIndex - 3),
                            allLines.getOrNull(lineIndex + 1),
                            allLines.getOrNull(lineIndex + 2),
                            allLines.getOrNull(lineIndex + 3)
                        )
                        val nearbyBrand = nearbyLines.firstNotNullOfOrNull { nearbyLine ->
                            PARCEL_KEYWORDS.firstOrNull { nearbyLine.text.contains(it) }
                        }
                        if (nearbyBrand != null) return nearbyBrand
                    }
                }

                PARCEL_KEYWORDS.firstOrNull { allText.contains(it) } ?: "快递"
            }
            else -> hint
        }
    }

    private fun isExcluded(code: String): Boolean {
        return EXCLUDE_PATTERNS.any { it.containsMatchIn(code) }
    }

    /**
     * 从 OCR 文本中提取取件地址
     * 匹配模式：\"代收点地址\"/\"取件地址\"/\"收货地址\" + 地址内容
     */
    fun extractAddress(lines: List<OCREngine.TextLine>, allText: String): String {
        val addressPrefixes = listOf("代收点地址", "取件地址", "收货地址", "地址")

        // 1. 同一行匹配："代收点地址：育新路北段..."
        for (line in lines) {
            for (prefix in addressPrefixes) {
                val idx = line.text.indexOf(prefix)
                if (idx >= 0) {
                    // 取前缀后面的内容（跳过 : ： 空格）
                    val after = line.text.substring(idx + prefix.length)
                        .trimStart(':', '：', ' ')
                    if (after.length >= 4) return after.take(50)
                }
            }
        }

        // 2. 跨行匹配：前缀一行，地址在下一行
        for (i in lines.indices) {
            val line = lines[i]
            val isPrefixLine = addressPrefixes.any { line.text.contains(it) }
            if (isPrefixLine && i + 1 < lines.size) {
                val nextLine = lines[i + 1].text.trim()
                // 下一行应该像地址（包含中文、数字、路/号/栋等）
                if (nextLine.length >= 4 && nextLine.any { it in '\u4e00'..'\u9fff' }) {
                    return nextLine.take(50)
                }
            }
        }

        return ""
    }
}
