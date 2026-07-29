package com.pickupcode.app.extractor

import android.graphics.Rect
import com.pickupcode.app.ocr.OCREngine

object CodeExtractor {

    data class ExtractedCode(val code: String, val type: CodeType, val source: String, val confidence: Float)
    enum class CodeType { pickup_food, pickup_parcel }

    private val THREE_SEGMENT_PARCEL = Regex("\\b(\\d{1,3})-(\\d{1,2})-(\\d{3,6})\\b")
    private val LETTER_TWO_SEGMENT_PARCEL = Regex("\\b([A-Z])-(\\d{1,2})-(\\d{3,4})\\b", RegexOption.IGNORE_CASE)
    private val LONG_NUMBER_PARCEL = Regex("\\b(\\d{6,8})\\b")
    private val LETTER_NUMBER_FOOD = Regex("\\b([A-Z]\\s*[-]?\\s*\\d{2,4})\\b", RegexOption.IGNORE_CASE)
    private val PURE_NUMBER_FOOD = Regex("\\b(?<![\\d-])(\\d{2,5})(?![\\d])\\b")
    private val PREFIXED_CODE = Regex("(取[餐件货单]码|取餐号|取单号|排号|券号|提取码)[:：]?\\s*([A-Za-z0-9\\-]{2,12})")

    private const val SCORE_PREFIXED = 100f; private const val SCORE_THREE_SEG = 95f
    private const val SCORE_LETTER_TWO_SEG = 85f; private const val SCORE_LETTER_NUM_FOOD = 80f
    private const val SCORE_PURE_NUM_FOOD = 75f; private const val SCORE_LONG_NUM_PARCEL = 60f
    private const val LARGE_FONT_HEIGHT_PX = 60; private const val FONT_SIZE_RATIO_THRESHOLD = 1.5f

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
        "菜鸟", "驿站", "丰巢", "妈妈驿站", "兔喜", "快递超市",
        "京东快递", "顺丰", "中通", "圆通", "申通", "韵达", "极兔",
        "取件码", "取货码", "提取码", "快递柜", "货架"
    )
    private val EXCLUDE_PATTERNS = listOf(
        Regex("\\b1[3-9]\\d{9}\\b"), Regex("\\b0\\d{2,3}-?\\d{7,8}\\b"),
        Regex("\\d{1,2}:\\d{2}"), Regex("[￥¥\$]\\s*\\d+"), Regex("\\d+\\.?\\d*\\s*[元块]"),
        Regex("\\b\\d{12,}\\b"), Regex("\\b\\d{4}年\\d{1,2}月\\b"),
        Regex("\\d+\\s*[个份件杯张条]"), Regex("\\d+\\s*[分钟小时]"), Regex("\\d+\\s*[号桌台]"),
        Regex("\\d+\\s*[号楼层室]"), Regex("\\d+\\s*[折]"), Regex("\\d+\\s*[分](?![钟])"),
        Regex("\\d+\\s*[毫厘克千克升毫升]")
    )

    fun extract(lines: List<OCREngine.TextLine>, screenHeight: Int = 0): List<ExtractedCode> {
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
                        SCORE_PREFIXED, sourceFromLine(line, "取餐码", lines, allText)))
                }
            }
        }

        val prefixKw = listOf("取餐码", "取餐号", "取单码", "取单号", "取件码", "取货码", "排号", "提取码")
        for (i in lines.indices) {
            if (prefixKw.any { lines[i].text.contains(it, ignoreCase = true) } && i + 1 < lines.size) {
                Regex("^\\s*(\\d{2,5})\\s*$").find(lines[i + 1].text)?.let { m ->
                    if (!isExcluded(m.value)) {
                        candidates.add(Candidate(m.value,
                            if (lines[i].text.contains("餐") || lines[i].text.contains("单")) CodeType.pickup_food else CodeType.pickup_parcel,
                            SCORE_PREFIXED, sourceFromLine(lines[i], "取餐码", lines, allText)))
                    }
                }
            }
        }

        data class Rule(val regex: Regex, val type: CodeType, val baseScore: Float,
                        val ctxBonus: Float = 0f, val sizeBonus: Boolean = false, val pureNum: Boolean = false)
        val rules = listOf(
            Rule(THREE_SEGMENT_PARCEL, CodeType.pickup_parcel, SCORE_THREE_SEG),
            Rule(LETTER_TWO_SEGMENT_PARCEL, CodeType.pickup_parcel, SCORE_LETTER_TWO_SEG),
            Rule(LONG_NUMBER_PARCEL, CodeType.pickup_parcel, SCORE_LONG_NUM_PARCEL, 10f),
            Rule(LETTER_NUMBER_FOOD, CodeType.pickup_food, SCORE_LETTER_NUM_FOOD, 10f, true),
            Rule(PURE_NUMBER_FOOD, CodeType.pickup_food, SCORE_PURE_NUM_FOOD, 10f, true, true)
        )

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
        val has3Seg = candidates.any { it.type == CodeType.pickup_parcel && THREE_SEGMENT_PARCEL.matches(it.code) }
        if (has3Seg) candidates.replaceAll { c -> if (c.type == CodeType.pickup_parcel && LONG_NUMBER_PARCEL.matches(c.code)) c.copy(score = c.score - 30f) else c }
        val hasOrder = allText.contains(Regex("\\b\\d{6,}-\\d{5,}\\b")) || allText.contains(Regex("\\b\\d{2}-\\d{5,}-\\d{4,}\\b"))
        if (hasOrder) candidates.replaceAll { c -> if (LONG_NUMBER_PARCEL.matches(c.code)) c.copy(score = c.score - 20f) else c }

        candidates.sortByDescending { it.score }
        val seen = mutableSetOf<String>()
        val results = mutableListOf<ExtractedCode>()
        val top = candidates.firstOrNull()?.score ?: 0f
        for (c in candidates) {
            if (c.code in seen) continue; seen.add(c.code)
            if (c.score >= top * 0.75f)
                results.add(ExtractedCode(c.code, c.type, c.source, (c.score / SCORE_PREFIXED).coerceIn(0f, 1f)))
        }
        return results
    }

    fun extractAddress(lines: List<OCREngine.TextLine>, allText: String): String {
        val explicit = listOf("代收点地址", "取件地址", "收货地址")
        for (line in lines) for (p in explicit) {
            val i = line.text.indexOf(p)
            if (i >= 0) {
                var a = line.text.substring(i + p.length).trimStart(':', '：', ' ')
                val bar = a.indexOf('|').takeIf { it >= 0 } ?: a.indexOf('｜')
                if (bar >= 0) a = a.substring(bar + 1).trim()
                if (isAddressLike(a)) return a.take(50)
            }
        }
        Regex("地址[:：]\\s*(.+)").find(allText)?.let { m ->
            val a = m.groupValues[1].trim()
            if (isAddressLike(a)) return a.take(50)
        }
        val prefixes = explicit + listOf("代收点", "地址")
        for (i in lines.indices) {
            if (!prefixes.any { lines[i].text.contains(it) }) continue
            val bar = lines[i].text.indexOf('|').takeIf { it >= 0 } ?: lines[i].text.indexOf('｜')
            if (bar >= 0) { val a = lines[i].text.substring(bar + 1).trim(); if (isAddressLike(a)) return a.take(50) }
            for (j in i + 1..minOf(i + 2, lines.lastIndex)) {
                val n = lines[j].text.trim()
                if (isAddressLike(n)) return n.take(50)
            }
        }
        for (line in lines) {
            Regex("（([^）]*[路街段柜]）").find(line.text)?.let { m -> if (isAddressLike(m.groupValues[1])) return m.groupValues[1].take(50) }
        }
        for (line in lines) {
            if (line.text.contains(Regex("[路街段柜]")) && isAddressLike(line.text.trim()))
                return line.text.trim().take(50)
        }
        return ""
    }

    private fun isAddressLike(s: String): Boolean {
        if (s.length !in 4..50 || s.none { it in '\u4e00'..'\u9fff' }) return false
        return listOf("展开", "复制", "拨打", "导航", "订阅").none { s.contains(it) }
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

    private fun sourceFromLine(line: OCREngine.TextLine, hint: String, allLines: List<OCREngine.TextLine>, allText: String): String {
        fun brand(list: List<String>, ignoreCase: Boolean, line: OCREngine.TextLine) =
            list.firstOrNull { line.text.contains(it, ignoreCase) }
        fun nearbyBrand(list: List<String>, ignoreCase: Boolean) = allLines.indexOfFirst { it === line }
            .takeIf { it >= 0 }?.let { idx ->
                (-3..3).filter { it != 0 }.mapNotNull { allLines.getOrNull(idx + it) }
                    .firstNotNullOfOrNull { brand(list, ignoreCase, it) }
            }
        return when (hint) {
            "food", "取餐码", "取餐号" -> brand(FOOD_BRAND_KEYWORDS, true, line) ?: nearbyBrand(FOOD_BRAND_KEYWORDS, true) ?: "餐饮"
            "parcel", "取件码", "取货码" -> brand(PARCEL_KEYWORDS, false, line) ?: nearbyBrand(PARCEL_KEYWORDS, false) ?: "快递"
            else -> hint
        }
    }

    private fun isExcluded(code: String) = EXCLUDE_PATTERNS.any { it.containsMatchIn(code) }
}
