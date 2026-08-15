package com.pickupcode.app.extractor

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 取件码到期时间提取：三层管线（竞品调研综合方案，2026-08-13）。
 *
 * 第一层 文本时限（zyx-1006 正则）：信文本写了时限 → [parseDeadline] 解析为时间戳。
 * 第二层 类别默认（BiuLand PickupExpirationPolicy 思路）：快递码无文本时限 → 默认 [DEFAULT_PARCEL_LIFETIME_MS]。
 * 第三层 跳过：取餐码即时消费（当场取），不做到期提醒。
 *
 * 解析细节参考 Ruij-Wang DeadlineParser：
 * - "今日/今天" → 当天 20:00（驿站营业尾段提醒，不是午夜）
 * - "X月X日" → 解析月日，若已过（跨年场景）自动 +1 年
 */
object ExpiryExtractor {

    /** 快递取件码默认存放时长：3 天（国内驿站常规）。 */
    const val DEFAULT_PARCEL_LIFETIME_MS = 3 * 24 * 60 * 60 * 1000L

    /** 提醒时间点：当天 20:00（参考 Ruij-Wang，非午夜）。 */
    private val REMIND_HOUR = LocalTime.of(20, 0)

    /** 文本时限触发词 + 取值（zyx-1006 expiry 正则）：取到标点为止。 */
    private val EXPIRY_TEXT_REGEX = Regex(
        "(?:请于|截止|有效期至|最晚|保管至|取件有效期)\\s*([^，,。；;|\\n]{3,20})"
    )

    /** 完整年月日形态（2026-08-15 / 2026年8月15日 / 2026/8/15 / 2026.8.15）。 */
    private val YEAR_MONTH_DAY_REGEX = Regex("(\\d{4})\\s*[年/.\\-]\\s*(\\d{1,2})\\s*[月/.\\-]\\s*(\\d{1,2})\\s*日?")

    /** "X月X日" 形态（含 8-15 / 08月15日 / 8.15 / 8/15 等变体）。 */
    private val MONTH_DAY_REGEX = Regex("(\\d{1,2})\\s*[月/.\\-]\\s*(\\d{1,2})\\s*日?")

    /**
     * 计算取件码到期时间戳（ms）。
     *
     * @param text OCR/短信原文（未归一化也可，内部处理）
     * @param type 码类型（pickup_food 直接返回 null——取餐码即时消费不提醒）
     * @param createdAt 入库时间戳 ms
     * @param zoneId 时区（默认系统时区；测试可显式传 Asia/Shanghai 保证确定性）
     * @return 到期时间戳；null = 无需提醒
     */
    fun expiryTimeFor(text: String, type: CodeExtractor.CodeType, createdAt: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long? {
        if (type == CodeExtractor.CodeType.pickup_food) return null
        if (type == CodeExtractor.CodeType.coupon) return null

        val expiryText = extractExpiryText(text)
        if (expiryText != null) {
            return parseDeadline(expiryText, createdAt, zoneId) ?: createdAt + DEFAULT_PARCEL_LIFETIME_MS
        }
        return createdAt + DEFAULT_PARCEL_LIFETIME_MS
    }

    /**
     * 第一层：从原文提取时限文本（如 "请于8月15日前取件" → "8月15日前取件" 截到标点）。
     * 无则 null。
     */
    fun extractExpiryText(text: String): String? {
        if (text.isBlank()) return null
        val normalized = CodeExtractor.normalizeText(text)
        return EXPIRY_TEXT_REGEX.find(normalized)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 第二层：把时限文本解析为时间戳。相对时间（今日/明天）→ 当天 20:00；绝对日期 → 解析 + 跨年 +1。
     *
     * - 含 "今日"/"今天" → 返回 createdAt 当天 20:00（用 [endOfDay]）
     * - 含 "明日"/"明天" → 返回 createdAt 次日 20:00
     * - 命中 [YEAR_MONTH_DAY_REGEX]（显式年份）→ 直接解析；日期早于 createdAt 当天（旧短信/OCR 残留）视为不可用 → null
     * - 命中 [MONTH_DAY_REGEX] → LocalDate(createdAt 年份, 月, 日)；若该日期早于 createdAt 当天（跨年），年份 +1；返回该日 20:00
     * - 以上都不满足 → null（调用方回退默认时长）
     */
    fun parseDeadline(text: String, createdAt: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long? {
        val postedDate = Instant.ofEpochMilli(createdAt).atZone(zoneId).toLocalDate()
        if (text.contains("今日") || text.contains("今天")) {
            return endOfDay(postedDate, zoneId)
        }
        if (text.contains("明日") || text.contains("明天")) {
            return endOfDay(postedDate.plusDays(1), zoneId)
        }
        // 完整年月日必须优先于 MONTH_DAY_REGEX：否则 "2026-08-15" 会被后者错配成"月=26"，
        // 且其残片（如 "6-08"）还会被跨年逻辑误加一年。命中即定案（含不可用场景），不让残片再匹配。
        YEAR_MONTH_DAY_REGEX.find(text)?.let { m ->
            val year = m.groupValues[1].toIntOrNull()
            val month = m.groupValues[2].toIntOrNull()
            val day = m.groupValues[3].toIntOrNull()
            if (year == null || month == null || day == null) return null
            // 非法月/日（如 8月32日）直接 LocalDate.of 会抛 DateTimeException；异常若沿
            // expiryTimeFor → 识别入库链路冒出会导致整轮崩溃。解析失败一律返回 null 回退默认时长。
            val candidate = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
            if (candidate.isBefore(postedDate)) return null
            return endOfDay(candidate, zoneId)
        }
        MONTH_DAY_REGEX.find(text)?.let { m ->
            val month = m.groupValues[1].toIntOrNull() ?: return@let
            val day = m.groupValues[2].toIntOrNull() ?: return@let
            val candidate = runCatching { LocalDate.of(postedDate.year, month, day) }.getOrNull() ?: return@let
            val adjusted = if (candidate.isBefore(postedDate)) candidate.plusYears(1) else candidate
            return endOfDay(adjusted, zoneId)
        }
        return null
    }

    /** 某天 20:00 的时间戳（提醒时间点）。 */
    private fun endOfDay(date: LocalDate, zoneId: ZoneId): Long =
        date.atTime(REMIND_HOUR).atZone(zoneId).toInstant().toEpochMilli()
}
