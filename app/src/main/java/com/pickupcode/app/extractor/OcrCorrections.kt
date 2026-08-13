package com.pickupcode.app.extractor

/**
 * OCR 词级纠错表（澎湃记 textCorrections / BiuLand applyTextCorrections 思路，2026-08-13）。
 *
 * 分层原则：
 * - 字符级归一化（全角/横杠/括号）留在 [CodeExtractor.normalizeText]——通用规则，永不改变
 * - 词级误读（品牌名/常用词的 OCR 高频错字）放本表——知识会随真实数据增长，数据化后不改代码可扩展
 *
 * 当前为内置默认表；预留用户自定义追加（SharedPreferences JSON 数组）与在线更新（可选，需与纯本地定位权衡）。
 * 调用点：normalizeText 之后、正则匹配之前。
 */
object OcrCorrections {

    /** 内置默认纠错对（from → to，顺序替换）。来源：真实用户反馈与日志确认的高频误读。 */
    private val DEFAULT_CORRECTIONS = linkedMapOf(
        "免喜" to "兔喜"   // 兔喜生活驿站的 OCR 误读
    )

    /** 按序应用全部纠错对；无命中时零开销返回原串。 */
    fun apply(text: String): String {
        var result = text
        for ((from, to) in DEFAULT_CORRECTIONS) {
            if (result.contains(from)) result = result.replace(from, to)
        }
        return result
    }
}
