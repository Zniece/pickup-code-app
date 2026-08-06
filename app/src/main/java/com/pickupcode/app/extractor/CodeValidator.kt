package com.pickupcode.app.extractor

import android.content.Context
import com.pickupcode.app.learner.PatternLearner

/** 码格式校验：合法格式白名单、排除规则与 pattern-ID 分类（自 CodeExtractor 拆出，R1）。 */
object CodeValidator {

    /** 5 个共享解析正则（CodeExtractor 识别用 + 本类的格式分类表用，单一归属）。 */
    internal val THREE_SEGMENT_PARCEL = Regex("\\b(\\d{1,3})-(\\d{1,2})-(\\d{3,6})\\b")
    internal val FOUR_SEGMENT_PARCEL = Regex("\\b([A-Za-z]?\\d{1,2})-(\\d{1,2})-(\\d{1,2})-(\\d{2,4})\\b")
    internal val LETTER_TWO_SEGMENT_PARCEL = Regex("\\b([A-Z])-(\\d{1,2})-(\\d{3,4})\\b", RegexOption.IGNORE_CASE)
    internal val LETTER_DASH_FIVE_PARCEL = Regex("\\b([A-Za-z])-?(\\d{5,6})\\b", RegexOption.IGNORE_CASE)
    internal val LONG_NUMBER_PARCEL = Regex("\\b(\\d{6,8})\\b")

    private const val PATTERN_PREFIXED = "PREFIXED_CODE"

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
        // PURE_NUMBER_FOOD：手动/AI 校验无上下文，收紧为 4-5 位，避免 2-3 位裸数字(42/123)被当合法码
        Regex("\\d{4,5}")
    )

    internal fun isExcluded(code: String, context: Context? = null) =
        EXCLUDE_PATTERNS.any { it.containsMatchIn(code) } ||
        // A3: 用户标记"不是取件码"的可学习排除片段
        PatternLearner.isLearnedExcluded(code, context)

    private val formatPatterns = linkedMapOf(
        "FOUR_SEGMENT_PARCEL" to FOUR_SEGMENT_PARCEL,
        "THREE_SEGMENT_PARCEL" to THREE_SEGMENT_PARCEL,
        "LETTER_TWO_SEGMENT_PARCEL" to LETTER_TWO_SEGMENT_PARCEL,
        "LETTER_DASH_FIVE_PARCEL" to LETTER_DASH_FIVE_PARCEL,
        "LONG_NUMBER_PARCEL" to LONG_NUMBER_PARCEL,
    )

    internal fun classifyFormat(code: String): String {
        for ((id, regex) in formatPatterns) {
            if (regex.matches(code)) return id
        }
        return PATTERN_PREFIXED
    }

    fun getPatternId(code: String): String = classifyFormat(code)
}
