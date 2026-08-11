package com.pickupcode.app.extractor

import android.graphics.Rect
import android.content.Context
import android.util.Log
import com.pickupcode.app.learner.PatternLearner
import com.pickupcode.app.ocr.OCREngine
// R1 拆分：品牌/地址/校验知识已移入 BrandResolver / AddressExtractor / CodeValidator，
// 此处用 member import 保持 extract() 函数体零改动
import com.pickupcode.app.extractor.BrandResolver.sourceFromLine
import com.pickupcode.app.extractor.BrandResolver.FOOD_BRAND_KEYWORDS
import com.pickupcode.app.extractor.CodeValidator.isExcluded
import com.pickupcode.app.extractor.CodeValidator.classifyFormat
import com.pickupcode.app.extractor.CodeValidator.THREE_SEGMENT_PARCEL
import com.pickupcode.app.extractor.CodeValidator.FOUR_SEGMENT_PARCEL
import com.pickupcode.app.extractor.CodeValidator.LETTER_TWO_SEGMENT_PARCEL
import com.pickupcode.app.extractor.CodeValidator.LETTER_DASH_FIVE_PARCEL
import com.pickupcode.app.extractor.CodeValidator.LONG_NUMBER_PARCEL

object CodeExtractor {

    data class ExtractedCode(val code: String, val type: CodeType, val source: String, val confidence: Float)
    enum class CodeType { pickup_food, pickup_parcel, coupon }

    // A8-3-3315: letter prefix + 3 dash-separated segments, e.g. locker codes (A/B/C prefix)
    private val LETTER_THREE_SEG_PARCEL = Regex("\\b([A-Za-z]\\d{1,2})-(\\d{1,2})-(\\d{3,6})\\b", RegexOption.IGNORE_CASE)
    private val LETTER_DASH_THREE_PARCEL = Regex("\\b([A-Za-z])-(\\d{3,4})\\b", RegexOption.IGNORE_CASE)
    private val LETTER_NUMBER_FOOD = Regex("\\b([A-Z]\\s*[-]?\\s*\\d{2,4})\\b", RegexOption.IGNORE_CASE)
    private val PURE_NUMBER_FOOD = Regex("\\b(?<![\\d-])(\\d{2,5})(?![\\d])\\b")
    private val PREFIXED_CODE = Regex("(取[餐件货单]码|取餐号|取单号|排号|券号|提取码)[:：]?\\s*(?:为|是)?\\s*([A-Za-z0-9\\-]{2,12})")
    // 菜鸟/驿站类通知标准句式：凭1-6-5020到...取（件）；容忍 OCR 在码值与方位词间插入空格
    private val PING_CODE = Regex("(?:凭|好评码|提取码|券号)[:：]?\\s*([A-Za-z0-9\\-]{2,12}?)\\s*(?=(?:到|至|去|领|取|在|格|号柜|菜鸟|驿站|快递柜))", RegexOption.IGNORE_CASE)

    // 跨行前缀：上一行是取件码/凭条等词 + 下一行开头是码（后接地址/通知等）；去掉行尾$锚点，
    // 否则"231607 到育新路..."这类码后跟真实地址的会被漏抓（需保留开头强锚定 + 后不能紧邻数字/破折号）
    private val NEXT_LINE_CODE = Regex("^\\s*([A-Za-z0-9\\-]{2,12})\\s*(?![-\\d])")
    private val CODE_KEYWORD_NEAR = Regex("(取[件餐货]码|取餐号|驿站|快递柜|自提柜|取件点)")
    // 裸前缀字+码开头、无空格分隔的行（跨行拼接判定，循环内匹配，提为常量避免重编译）
    private val REG_BARE_PREFIX_LINE = Regex("^[餐件货单]码[A-Za-z0-9].*")
    private val ORDER_LONG_SQL = Regex("\\b\\d{6,}-\\d{5,}\\b")
    private val ORDER_SHORT_SQL = Regex("\\b\\d{2,4}-\\d{3,4}-\\d{4,}\\b")

    private const val SCORE_PREFIXED = 100f; private const val SCORE_THREE_SEG = 95f
    private const val SCORE_FOUR_SEG = 95f
    private const val SCORE_LETTER_TWO_SEG = 85f
    private const val SCORE_LETTER_DASH_FIVE = 85f
    private const val SCORE_LETTER_DASH_THREE = 80f
    private const val SCORE_LETTER_NUM_FOOD = 80f
    private const val SCORE_PURE_NUM_FOOD = 75f; private const val SCORE_LONG_NUM_PARCEL = 60f
    private const val LARGE_FONT_HEIGHT_PX = 60; private const val FONT_SIZE_RATIO_THRESHOLD = 1.5f

    // PING_CODE（凭条号）评分：略低于前缀码，命中驿站/快递柜或三段式码再加分
    private const val PING_BASE_PENALTY = 2f
    private const val PING_PARCEL_BONUS = 8f
    private const val PING_MULTISEG_BONUS = 10f

    private val FOOD_KEYWORDS = FOOD_BRAND_KEYWORDS + listOf(
        "取餐", "取餐码", "取餐号", "取单码", "取单号", "请取餐", "正在制作", "等待取餐"
    )
    private val PARCEL_KEYWORDS = listOf(
        "菜鸟", "驿站", "丰巢", "妈妈驿站", "兔喜", "免喜", "快递超市",
        "京东快递", "顺丰", "中通", "圆通", "申通", "韵达", "极兔", "邮政",
        "取件码", "取货码", "提取码", "快递柜", "货架", "韵达超市", "欢猫智柜"
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
                        SCORE_PREFIXED, sourceFromLine(line, p, lines, allText), strong = true))
                }
            }
            // 跨行：OCR 常把「取件码/凭取」拆成两行（如 上一行结尾「取」+ 本行「件码067865」）
            if (i > 0) {
                val prev = lines[i - 1].text.trim()
                // 仅当本行以裸前缀字+码开头（件/餐/货/单+码）且无空格分隔，才尝试拼接上一行尾字
                if (line.text.trim().matches(REG_BARE_PREFIX_LINE)) {
                    val joined = prev.takeLast(1) + line.text.trim()
                    PREFIXED_CODE.find(joined)?.let { m ->
                        val code = m.groupValues[2]
                        if (!isExcluded(code)) {
                            val p = m.groupValues[1]
                            candidates.add(Candidate(code,
                                if (p.contains("餐") || p.contains("单")) CodeType.pickup_food else CodeType.pickup_parcel,
                                SCORE_PREFIXED, sourceFromLine(line, p, lines, allText), strong = true))
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
                        SCORE_PREFIXED, sourceFromLine(lines[i], if (isFood) "取餐码" else "取件码", lines, allText), strong = true))
                }
            }
        }

        data class Rule(val regex: Regex, val type: CodeType, val baseScore: Float,
                        val ctxBonus: Float = 0f, val sizeBonus: Boolean = false, val pureNum: Boolean = false,
                        val minMatchLen: Int = 0, val isLearned: Boolean = false, val strong: Boolean = false)

        // 凭条号句式（凭1-6-5020到...取）：菜鸟驿站/快递柜典型通知，优先且绕过 food 上下文干扰
        for (line in lines) {
            PING_CODE.findAll(line.text).forEach matchLoop@{ m ->
                val code = m.groupValues[1]
                if (isExcluded(code) || code.length < 2) return@matchLoop
                var s = SCORE_PREFIXED - PING_BASE_PENALTY
                if (PARCEL_KEYWORDS.any { line.text.contains(it) }) s += PING_PARCEL_BONUS
                if (THREE_SEGMENT_PARCEL.matches(code) || FOUR_SEGMENT_PARCEL.matches(code)) s += PING_MULTISEG_BONUS
                candidates.add(Candidate(code, CodeType.pickup_parcel, s,
                    sourceFromLine(line, "凭条号", lines, allText), strong = true))
            }
        }

        val rules = mutableListOf(
            Rule(THREE_SEGMENT_PARCEL, CodeType.pickup_parcel, SCORE_THREE_SEG, strong = true),
            Rule(FOUR_SEGMENT_PARCEL, CodeType.pickup_parcel, SCORE_FOUR_SEG, strong = true),
            Rule(LETTER_TWO_SEGMENT_PARCEL, CodeType.pickup_parcel, SCORE_LETTER_TWO_SEG, strong = true),
            Rule(LETTER_DASH_FIVE_PARCEL, CodeType.pickup_parcel, SCORE_LETTER_DASH_FIVE, strong = true),
            Rule(LETTER_THREE_SEG_PARCEL, CodeType.pickup_parcel, SCORE_THREE_SEG, strong = true),
            Rule(LETTER_DASH_THREE_PARCEL, CodeType.pickup_parcel, SCORE_LETTER_DASH_THREE, strong = true),
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
                // A1: 用户手动停用的规则不再参与识别
                if (!rule.enabled) continue
                try {
                    val regex = Regex(rule.regex)
                    val type = if (rule.type == "pickup_food") CodeType.pickup_food else CodeType.pickup_parcel
                    // 已学规则基础分低；B3: 若已衰减(超期未用)则进一步压到极低分，仍参与但不抢先，
                    // 若后续真实被用到会经 touchRule 解除衰减 —— 让衰减可自愈，而非单向永久弃用。
                    val base = if (rule.decayed) 20f else 65f
                    rules.add(Rule(regex, type, base, 10f, minMatchLen = 3, isLearned = true))
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

                    // B3: 命中已学规则 → 刷新其 lastUsedAt 并解除衰减（用 isLearned 标识，比 baseScore 判等更稳）
                    if (context != null && rule.isLearned && m.value.length >= 3) {
                        regexToLearned[rule.regex.pattern]?.let { r ->
                            PatternLearner.touchRule(context, r)
                        }
                    }

                    candidates.add(Candidate(m.value, rule.type, s, sourceFromLine(line,
                        if (rule.type == CodeType.pickup_food) "food" else "parcel", lines, allText), strong = rule.strong))
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
            // 修复多通知同屏漏识别：强上下文证据码(PREFIXED/凭条/段式)不过 top×0.75 阈值，
            // 只对无证据的弱候选(纯数字噪声)做 top×0.75 过滤，避免高分码拖死同屏次高分真实码。
            if (c.strong || c.score >= top * 0.75f)
                results.add(ExtractedCode(c.code, c.type, c.source, (c.score / SCORE_PREFIXED).coerceIn(0f, 1f)))
        }
        if (context != null) recordLearning(context, results, allText, source)
        return results
    }

    private data class Candidate(val code: String, val type: CodeType, val score: Float, val source: String, val strong: Boolean = false)

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

    // ---------------------------------------------------------------
    // Pattern learning feedback
    // ---------------------------------------------------------------

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
}
