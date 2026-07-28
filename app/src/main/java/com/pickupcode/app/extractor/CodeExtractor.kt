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
    private val PURE_NUMBER_FOOD = Regex("\\b(?<![\\d-])(\\d{2,4})(?![\\d])\\b")

    // 取餐码 — 字母+数字（肯德基 A-356 等）
    private val LETTER_NUMBER_FOOD = Regex("\\b([A-Z]\\s*[-]?\\s*\\d{2,4})\\b", RegexOption.IGNORE_CASE)

    // 取件码 — 三段式（菜鸟 10-2-7507、妈妈驿站 2-2-1521）
    private val THREE_SEGMENT_PARCEL = Regex("\\b(\\d{1,3})-(\\d{1,2})-(\\d{3,6})\\b")

    // 取件码 — 字母+两段式（兔喜 A-26-001）
    private val LETTER_TWO_SEGMENT_PARCEL = Regex("\\b([A-Z])-(\\d{1,2})-(\\d{3,4})\\b", RegexOption.IGNORE_CASE)

    // 取件码 — 纯数字长码（丰巢、京东快递柜）
    private val LONG_NUMBER_PARCEL = Regex("\\b(\\d{6,8})\\b")

    // 带前缀中文的格式（兜底）— 最高优先级
    private val PREFIXED_CODE = Regex("(取[餐件货]码|取餐号|排号|券号|提取码)[:：]?\\s*([A-Za-z0-9\\-]{2,12})")

    // === 评分常量 ===
    private const val SCORE_PREFIXED = 100f
    private const val SCORE_THREE_SEG = 90f
    private const val SCORE_LETTER_TWO_SEG = 85f
    private const val SCORE_LETTER_NUM_FOOD = 80f
    private const val SCORE_PURE_NUM_FOOD = 75f
    private const val SCORE_LONG_NUM_PARCEL = 70f
    private const val SCORE_FALLBACK = 60f

    private const val LARGE_FONT_HEIGHT_PX = 60

    private val FOOD_BRANDS = listOf(
        "瑞幸", "luckin", "星巴克", "starbucks", "麦当劳", "mcdonald",
        "肯德基", "kfc", "喜茶", "heytea", "奈雪", "蜜雪冰城",
        "霸王茶姬", "茶百道", "一点点", "coco", "书亦", "古茗",
        "茶颜悦色", "沪上阿姨", "甜啦啦", "益禾堂",
        "取餐", "取餐码", "取餐号", "请取餐", "正在制作", "等待取餐"
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
        Regex("[￥¥\$]\\s*\\d+"),               // 金额
        Regex("\\b\\d{12,}\\b"),               // 订单号(长数字)
        Regex("\\b\\d{4}年\\d{1,2}月\\b"),    // 日期
    )

    fun extract(lines: List<OCREngine.TextLine>, screenHeight: Int = 0): List<ExtractedCode> {
        val candidates = mutableListOf<Candidate>()

        val allText = lines.joinToString(" ") { it.text }
        val isFoodContext = FOOD_BRANDS.any { allText.contains(it, ignoreCase = true) }
        val isParcelContext = PARCEL_KEYWORDS.any { allText.contains(it) }

        // 1. 中文前缀匹配（最高优先级）
        PREFIXED_CODE.findAll(allText).forEach { match ->
            val code = match.groupValues[2]
            if (!isExcluded(code)) {
                val prefix = match.groupValues[1]
                val type = if (prefix.contains("取餐") || prefix.contains("餐"))
                    CodeType.pickup_food else CodeType.pickup_parcel
                candidates.add(Candidate(code, type, SCORE_PREFIXED, extractSource(allText, prefix)))
            }
        }

        // 2 & 3. 单行匹配 — 统一规则表驱动
        data class MatchRule(
            val regex: Regex,
            val type: CodeType,
            val baseScore: Float,
            val sourceHint: String,
            val contextRequired: Boolean,  // true = 需要对应上下文才匹配
            val needsSizeBonus: Boolean = false
        )

        val rules = listOf(
            MatchRule(THREE_SEGMENT_PARCEL, CodeType.pickup_parcel, SCORE_THREE_SEG, "parcel", contextRequired = false),
            MatchRule(LETTER_TWO_SEGMENT_PARCEL, CodeType.pickup_parcel, SCORE_LETTER_TWO_SEG, "parcel", contextRequired = false),
            MatchRule(LONG_NUMBER_PARCEL, CodeType.pickup_parcel, SCORE_LONG_NUM_PARCEL, "parcel", contextRequired = true),
            MatchRule(LETTER_NUMBER_FOOD, CodeType.pickup_food, SCORE_LETTER_NUM_FOOD, "food", contextRequired = true, needsSizeBonus = true),
            MatchRule(PURE_NUMBER_FOOD, CodeType.pickup_food, SCORE_PURE_NUM_FOOD, "food", contextRequired = true, needsSizeBonus = true),
        )

        for (line in lines) {
            val posBonus = positionBonus(line, screenHeight)
            val sizeBonus = fontSizeBonus(line)

            for (rule in rules) {
                // 上下文检查
                if (rule.contextRequired) {
                    val ok = when (rule.type) {
                        CodeType.pickup_food -> isFoodContext
                        CodeType.pickup_parcel -> isParcelContext
                    }
                    if (!ok) continue
                }

                rule.regex.findAll(line.text).forEach { match ->
                    if (!isExcluded(match.value)) {
                        val bonus = if (rule.needsSizeBonus) posBonus + sizeBonus else posBonus
                        candidates.add(Candidate(
                            match.value, rule.type,
                            rule.baseScore + bonus,
                            extractSource(allText, rule.sourceHint)
                        ))
                    }
                }
            }
        }

        // 无语境兜底 — 只尝试高度可信的三段式
        if (candidates.isEmpty() && !isFoodContext && !isParcelContext) {
            for (line in lines) {
                THREE_SEGMENT_PARCEL.find(line.text)?.let {
                    candidates.add(Candidate(it.value, CodeType.pickup_parcel, SCORE_FALLBACK, "快递"))
                }
            }
        }

        if (candidates.isEmpty()) return emptyList()

        candidates.sortByDescending { it.score }

        // 去重：相同 code 保留分数最高的
        val seen = mutableSetOf<String>()
        val results = mutableListOf<ExtractedCode>()
        for (c in candidates) {
            if (c.code in seen) continue
            seen.add(c.code)
            val conf = (c.score / SCORE_PREFIXED).coerceIn(0f, 1f)
            if (conf >= 0.3f) {
                results.add(ExtractedCode(c.code, c.type, c.source, conf))
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

    private fun fontSizeBonus(line: OCREngine.TextLine): Float {
        val box = line.boundingBox ?: return 0f
        return if (box.height() > LARGE_FONT_HEIGHT_PX) 10f else 0f
    }

    private fun extractSource(allText: String, hint: String): String {
        return when (hint) {
            "food" -> FOOD_BRANDS.firstOrNull { allText.contains(it, ignoreCase = true) } ?: "餐饮"
            "parcel" -> PARCEL_KEYWORDS.firstOrNull { allText.contains(it) } ?: "快递"
            "取件码", "取货码" -> PARCEL_KEYWORDS.firstOrNull { allText.contains(it) } ?: "快递"
            "取餐码", "取餐号" -> FOOD_BRANDS.firstOrNull { allText.contains(it, ignoreCase = true) } ?: "餐饮"
            else -> hint
        }
    }

    private fun isExcluded(code: String): Boolean {
        return EXCLUDE_PATTERNS.any { it.containsMatchIn(code) }
    }
}
