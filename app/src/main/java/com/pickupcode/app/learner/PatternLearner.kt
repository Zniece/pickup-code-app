package com.pickupcode.app.learner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object PatternLearner {

    private const val PREFS = "pattern_learner"
    private const val KEY_TOTAL = "total_scans"
    private const val KEY_ATTEMPTS = "attempts"
    private const val KEY_MISSES = "misses"
    private const val KEY_VERIFIED = "verified"
    private const val KEY_PAT_PREFIX = "pat_"
    private const val MAX_UNMATCHED = 100
    private const val MIN_SUGGEST = 3

    // 候选码段提取 — 从乱文本中抠出可能是码的孤立数字/字母数字+连字符段。
    // 注意：原始字符串里用单反斜杠(\d/\b)，双反斜杠会匹配不到（正则双重转义 bug）。
    // 不使用 \b 边界（对中文/OCR 混排文本不可靠），改用结构正则 + 前后否定断言。
    private val SEG_CANDIDATE = Regex(
        """[A-Za-z]?\d{1,2}-\d{1,2}-\d{3,6}|\d{3,6}-\d{3,6}|[A-Za-z]-\d{4,6}""",
        RegexOption.IGNORE_CASE
    )
    // 纯数字候选：3-6 位，且前后不能是数字/字母（避免截断长订单号、电话等）
    private val PURE_CANDIDATE = Regex("""(?<![\dA-Za-z])(\d{3,6})(?![\dA-Za-z])""")

    // 候选排除上下文 — 避免价格/数量/时长/楼层/度量等干扰片段被喂入学习池
    private val CANDIDATE_EXCLUDE_CTX = Regex(
        """(?:\d+[元块]|\d+[份件个杯]|\d+[分钟]|\d+[号号楼栋]|""" +
        """\d+[折]|\d+[毫升升]|x\d{1,2}\b|\d{8,})""",
        RegexOption.IGNORE_CASE
    )

    /** 从一段 OCR 文本中提取候选码段。先找带连字符的完整码段，再退而求其次找孤立纯数字。 */
    private fun extractCodeCandidates(text: String): List<String> {
        val seg = SEG_CANDIDATE.find(text)
        if (seg != null) {
            val c = seg.value
            if (c.length in 5..12) return listOf(c)
        }
        return PURE_CANDIDATE.findAll(text).map { it.groupValues[1] }.toList()
    }

    data class PatternStats(
        val totalScans: Int,
        val attempts: Int,
        val misses: Int,
        val verified: Int,
        val perPattern: Map<String, Int>
    )

    data class PatternSuggestion(
        val tokenPattern: String,
        val label: String,
        val sampleCodes: List<String>,
        val count: Int,
        val confidence: Float,
        val proposedRegex: String
    )

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    // ---------------------------------------------------------------
    // B2: 每日命中率统计（stats_log.json，供命中率曲线）
    // ---------------------------------------------------------------

    private const val KEY_DAY_STATS = "day_stats"
    private const val MAX_DAY_STATS = 30

    private fun todayKey(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return fmt.format(java.util.Date())
    }

    /** 记录当天的 {total, hits, misses}，供命中率曲线。 */
    private fun recordDay(context: Context, isHit: Boolean, isMiss: Boolean) {
        // M10: 加锁防并发 read-modify-write 丢计数（识别路径虽多串行，但多入口仍可能有并发写）
        synchronized(dayStatsLock) {
            val key = todayKey()
            val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DAY_STATS, null)
            val map = linkedMapOf<String, JSONObject>()
            if (json != null) {
                try {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        map[o.optString("date")] = o
                    }
                } catch (_: Exception) {}
            }
            val today = map[key] ?: JSONObject().apply { put("date", key); put("total", 0); put("hits", 0); put("misses", 0) }
            today.put("total", today.optInt("total") + 1)
            if (isHit) today.put("hits", today.optInt("hits") + 1)
            if (isMiss) today.put("misses", today.optInt("misses") + 1)
            map[key] = today
            // 只保留最近 MAX_DAY_STATS 天
            val sorted = map.values.sortedBy { it.optString("date") }.takeLast(MAX_DAY_STATS)
            val out = JSONArray()
            for (o in sorted) out.put(o)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_DAY_STATS, out.toString()).apply()
        }
    }

    private val dayStatsLock = Any()

    /** 每日命中率序列：按日期升序的 {date, total, hits, misses}。 */
    data class DayStat(val date: String, val total: Int, val hits: Int, val misses: Int)
    fun getDailyStats(context: Context): List<DayStat> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DAY_STATS, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                DayStat(o.optString("date"), o.optInt("total"), o.optInt("hits"), o.optInt("misses"))
            }
        } catch (_: Exception) { emptyList() }
    }

    // ---------------------------------------------------------------
    // C2: 常用取件点归并（pickup_points.json）
    // ---------------------------------------------------------------

    private const val KEY_PICKUP_POINTS = "pickup_points"
    private const val MAX_PICKUP_POINTS = 50

    data class PickupPoint(val name: String, val count: Int, val lastUsedAt: Long)

    /** 记录一次取件地址出现（识别/标记已取时调用），用于归并"常用取件点"。 */
    // B13: read-modify-write 需原子化——识别线程与详情页「标记已取」可并发调用，无锁会丢失计数
    @Synchronized
    fun registerPickupPoint(context: Context, address: String) {
        if (address.isBlank()) return
        val key = address.trim()
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PICKUP_POINTS, null)
        val map = linkedMapOf<String, PickupPoint>()
        if (json != null) {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    map[o.optString("address")] = PickupPoint(o.optString("name"), o.optInt("count"), o.optLong("last", 0L))
                }
            } catch (_: Exception) {}
        }
        val prev = map[key]
        map[key] = PickupPoint(key, (prev?.count ?: 0) + 1, System.currentTimeMillis())
        val sorted = map.entries.sortedByDescending { it.value.count }.take(MAX_PICKUP_POINTS)
        val out = JSONArray()
        for ((addr, p) in sorted) {
            out.put(JSONObject().apply { put("address", addr); put("name", p.name); put("count", p.count); put("last", p.lastUsedAt) })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PICKUP_POINTS, out.toString()).apply()
    }

    /** 该地址是否已是常用取件点（出现 >= 2 次）。返回 null 表示不是。 */
    fun isFrequentPickupPoint(context: Context, address: String): PickupPoint? {
        if (address.isBlank()) return null
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PICKUP_POINTS, null)
            ?: return null
        return try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("address") == address.trim() && o.optInt("count") >= 2) {
                    return PickupPoint(o.optString("name"), o.optInt("count"), o.optLong("last", 0L))
                }
            }
            null
        } catch (_: Exception) { null }
    }

    /** Record that the extractor matched a code using this pattern.
     *  This is NOT a correctness signal — just pattern usage tracking. */
    fun recordAttempt(context: Context, patternId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_TOTAL, prefs.getInt(KEY_TOTAL, 0) + 1)
            .putInt(KEY_ATTEMPTS, prefs.getInt(KEY_ATTEMPTS, 0) + 1)
            .putInt(KEY_PAT_PREFIX + patternId, prefs.getInt(KEY_PAT_PREFIX + patternId, 0) + 1)
            .apply()
        recordDay(context, isHit = true, isMiss = false)
    }

    /** Record that the extractor found nothing in the OCR output.
     *  仅轻量记录；autoApply（读文件+聚类+写规则）通过低频节流触发，避免每次 miss 都做重 IO。
     *  @param source B1 样本来源打标：share / sms / screen / manual / notify */
    fun recordMiss(context: Context, rawText: String, source: String = "unknown") {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_TOTAL, prefs.getInt(KEY_TOTAL, 0) + 1)
            .putInt(KEY_MISSES, prefs.getInt(KEY_MISSES, 0) + 1)
            .apply()
        recordDay(context, isHit = false, isMiss = true)
        appendUnmatched(context, rawText, source)
        // 低频节流触发：距上次自动学习至少间隔后才重跑，避免高频 IO
        autoApplyThrottled(context)
    }

    /** Record that a user confirmed an extracted code was correct.
     *  Call this from notification tap / manual verification UI. */
    fun recordVerified(context: Context, patternId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_VERIFIED, prefs.getInt(KEY_VERIFIED, 0) + 1)
            .putInt(KEY_PAT_PREFIX + patternId + "_ok", prefs.getInt(KEY_PAT_PREFIX + patternId + "_ok", 0) + 1)
            .apply()
    }

    /** Record that a user marked an extracted code as incorrect. */
    fun recordCodeIncorrect(context: Context, patternId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_PAT_PREFIX + patternId + "_bad", prefs.getInt(KEY_PAT_PREFIX + patternId + "_bad", 0) + 1)
            .apply()
    }

    /** Record that a user confirmed an extracted source name (courier/restaurant) was correct. */
    fun recordSourceMatch(context: Context, sourceName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_PAT_PREFIX + "src_" + sourceName + "_ok", prefs.getInt(KEY_PAT_PREFIX + "src_" + sourceName + "_ok", 0) + 1)
            .apply()
    }

    /** Record that a user marked an extracted source name as incorrect. */
    fun recordSourceIncorrect(context: Context, sourceName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_PAT_PREFIX + "src_" + sourceName + "_bad", prefs.getInt(KEY_PAT_PREFIX + "src_" + sourceName + "_bad", 0) + 1)
            .apply()
    }

    fun getStats(context: Context): PatternStats {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val total = prefs.getInt(KEY_TOTAL, 0)
        val attempts = prefs.getInt(KEY_ATTEMPTS, 0)
        val misses = prefs.getInt(KEY_MISSES, 0)
        val verified = prefs.getInt(KEY_VERIFIED, 0)
        val per = mutableMapOf<String, Int>()
        for (key in prefs.all.keys) {
            // Only count raw pattern attempt counters, skip _ok/_bad/_verified/source sub-keys
            if (key.startsWith(KEY_PAT_PREFIX) &&
                !key.endsWith("_verified") && !key.endsWith("_ok") && !key.endsWith("_bad") &&
                !key.contains("_src_")
            ) {
                per[key.removePrefix(KEY_PAT_PREFIX)] = prefs.getInt(key, 0)
            }
        }
        return PatternStats(total, attempts, misses, verified, per)
    }

    fun getSuggestions(context: Context): List<PatternSuggestion> {
        val samples = loadUnmatched(context)
        if (samples.size < MIN_SUGGEST) return emptyList()

        val clustered = mutableMapOf<String, MutableList<String>>()
        for (s in samples) {
            val text = s.optString("text", "")
            // 先抠候选码段，再对每个码段 tokenize 聚类 —— 不再对整句脏文本 tokenize
            val candidates = extractCodeCandidates(text)
            for (cand in candidates) {
                // 排除上下文干扰（价格/数量/时长/楼层/度量等）
                if (CANDIDATE_EXCLUDE_CTX.containsMatchIn(cand)) continue
                val tok = tokenize(cand)
                if (tok.length >= 1) {
                    clustered.getOrPut(tok) { mutableListOf() }.add(cand)
                }
            }
        }

        val maxCount = clustered.values.maxOfOrNull { it.size } ?: return emptyList()
        return clustered
            .filter { it.value.size >= MIN_SUGGEST }
            .map { (tok, codes) ->
                PatternSuggestion(
                    tokenPattern = tok,
                    label = humanLabel(tok),
                    sampleCodes = codes.distinct().take(5),
                    count = codes.size,
                    confidence = (codes.size.toFloat() / maxCount).coerceAtMost(1f),
                    proposedRegex = tokenToRegex(tok)
                )
            }
            .sortedByDescending { it.count }
    }

    fun clearUnmatched(context: Context) {
        val file = File(context.filesDir, "unmatched_samples.json")
        file.writeText("[]")
    }

    // ---------------------------------------------------------------
    // A3: 可学习排除词（用户标记"不是取件码"的片段 → 学习池，之后识别剔除）
    // ---------------------------------------------------------------

    private const val KEY_EXCLUDES = "learned_excludes"
    private const val MAX_EXCLUDES = 100

    /** 把用户标记"不是取件码"的码值/片段加入可学习排除列表。 */
    fun addExclude(context: Context, token: String) {
        if (token.isBlank()) return
        val excludes = getLearnedExcludes(context).toMutableSet()
        excludes.add(token.trim().take(20))
        val arr = JSONArray()
        for (e in excludes.take(MAX_EXCLUDES)) arr.put(e)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_EXCLUDES, arr.toString()).apply()
        excludeCache = excludes   // 立即刷新进程内缓存
        excludeCacheAt = System.currentTimeMillis()
    }

    /** 当前可学习的排除片段。 */
    fun getLearnedExcludes(context: Context): Set<String> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EXCLUDES, null)
            ?: return emptySet()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    // 进程内缓存，避免识别热循环里每次候选码都重复 read+parse JSON
    @Volatile private var excludeCache: Set<String>? = null
    @Volatile private var excludeCacheAt = 0L
    private const val EXCLUDE_CACHE_MS = 2000L

    private fun cachedLearnedExcludes(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        val cached = excludeCache
        if (cached != null && now - excludeCacheAt < EXCLUDE_CACHE_MS) return cached
        val fresh = getLearnedExcludes(context)
        excludeCache = fresh
        excludeCacheAt = now
        return fresh
    }

    /** 判断某码值是否命中已学习的排除项（供 CodeExtractor 识别时剔除）。
     * 用完整值匹配而非 contains 子串：排除 "42" 不应误杀 "9421"/"421" 这类合法码。 */
    fun isLearnedExcluded(code: String, context: Context?): Boolean {
        if (context == null) return false
        val excludes = cachedLearnedExcludes(context)
        if (excludes.isEmpty()) return false
        return excludes.any { ex -> code.equals(ex, ignoreCase = true) }
    }

    // ---------------------------------------------------------------
    // Tokenize: string -> character-class pattern
    // ---------------------------------------------------------------

    private fun tokenize(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isDigit() -> sb.append('d')
                c.isLetter() -> sb.append('L')
                c == '-' -> sb.append('-')
                c == '_' -> sb.append('_')
                c == ' ' -> sb.append(' ')
                c == '.' -> sb.append('.')
                else -> sb.append('X')
            }
            i++
        }
        // Collapse consecutive same tokens
        val collapsed = StringBuilder()
        var last = '\u0000'
        var lastRun = 1
        for (j in 0 until sb.length) {
            val t = sb[j]
            if (t == last) {
                lastRun++
            } else {
                if (last != '\u0000') {
                    collapsed.append(last)
                    if (lastRun > 1) collapsed.append(lastRun)
                }
                last = t
                lastRun = 1
            }
        }
        if (last != '\u0000') {
            collapsed.append(last)
            if (lastRun > 1) collapsed.append(lastRun)
        }
        return collapsed.toString()
    }

    // ---------------------------------------------------------------
    // Human-readable label for a token pattern
    // ---------------------------------------------------------------

    private fun humanLabel(tok: String): String {
        return when (tok) {
            "d6" -> "6-digit number"
            "d7" -> "7-digit number"
            "d8" -> "8-digit number"
            "d1-d1-d4" -> "rack-shelf-slot (A-B-CCCC)"
            "d1-d1-d5" -> "rack-shelf-slot (A-B-CCCCC)"
            "d2-d1-d4" -> "rack-shelf-slot (AA-B-CCCC)"
            "L1-d5" -> "letter-5digit (like D-06003)"
            "L1-d6" -> "letter-6digit"
            "L2-d5" -> "2letter-5digit"
            "d5" -> "5-digit code"
            "d4" -> "4-digit code"
            "d3" -> "3-digit code"
            "L1-d2-d3" -> "letter-digit-digit (A-1-234)"
            "L1-d1-d4" -> "letter-digit-4digit"
            "d-d-d" -> "digit-dash-dash (1-2-3)"
            "d-d-d4" -> "digit-dash-4digit (1-6-5020)"
            "d-d-d5" -> "digit-dash-5digit"
            "Ld-d-d4" -> "letter-digit-dash-4digit (A8-3-3315)"
            else -> tok
        }
    }

    // ---------------------------------------------------------------
    // Convert token pattern -> candidate regex
    // ---------------------------------------------------------------

    private fun tokenToRegex(tok: String): String {
        val parts = parseRuns(tok)
        val sb = StringBuilder("\\b")
        for ((cls, count) in parts) {
            sb.append(when (cls) {
                'd' -> if (count == 1) "\\d" else "\\d{$count}"
                'L' -> if (count == 1) "[A-Za-z]" else "[A-Za-z]{$count}"
                '-' -> "-"
                '_' -> "_"
                ' ' -> "\\s*"
                '.' -> "\\."
                else -> "."
            })
        }
        sb.append("\\b")
        return sb.toString()
    }

    private data class Run(val cls: Char, val count: Int)

    private fun parseRuns(tok: String): List<Run> {
        val runs = mutableListOf<Run>()
        var i = 0
        while (i < tok.length) {
            val cls = tok[i]
            i++
            var cnt = 0
            while (i < tok.length && tok[i].isDigit()) {
                cnt = cnt * 10 + (tok[i] - '0')
                i++
            }
            runs.add(Run(cls, if (cnt > 0) cnt else 1))
        }
        return runs
    }

    // ---------------------------------------------------------------
    // Unmatched sample storage (JSON file, max 100 entries)
    // ---------------------------------------------------------------

    // 对 JSON 样本文件的写操作统一加锁，避免并发 read-modify-write 竞态导致丢失样本
    private val unmatchedLock = Any()
private val verifiedAddrLock = Any()

    private fun appendUnmatched(context: Context, rawText: String, source: String = "unknown") {
        if (rawText.isBlank()) return
        synchronized(unmatchedLock) {
            val file = File(context.filesDir, "unmatched_samples.json")
            val arr = if (file.exists()) {
                try { JSONArray(file.readText()) } catch (_: Exception) { JSONArray() }
            } else JSONArray()

            // Keep only recent + relevant text
            val snippet = rawText.take(300)
            arr.put(JSONObject().apply {
                put("text", snippet)
                put("src", source)          // B1: 样本来源打标
                put("ts", System.currentTimeMillis() / 1000)
            })

            // Trim to max
            while (arr.length() > MAX_UNMATCHED) arr.remove(0)
            file.writeText(arr.toString())
        }
    }

    private fun loadUnmatched(context: Context): List<JSONObject> {
        val file = File(context.filesDir, "unmatched_samples.json")
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (_: Exception) { emptyList() }
    }

    // ---------------------------------------------------------------
    // Address verification tracking
    // ---------------------------------------------------------------

    fun recordAddressVerified(context: Context, address: String, confidence: Float) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val verified = prefs.getInt("addr_verified", 0)
        val total = prefs.getInt("addr_total", 0)
        prefs.edit()
            .putInt("addr_verified", verified + 1)
            .putInt("addr_total", total + 1)
            .apply()

        // M9: verified_addresses.json 写操作加锁（与 unmatched_samples.json 一致），避免并发 read-modify-write 丢样本
        synchronized(verifiedAddrLock) {
            val file = File(context.filesDir, "verified_addresses.json")
            val arr = if (file.exists()) {
                try { JSONArray(file.readText()) } catch (_: Exception) { JSONArray() }
            } else JSONArray()
            arr.put(JSONObject().apply {
                put("address", address)
                put("confidence", confidence.toDouble())
                put("ts", System.currentTimeMillis() / 1000)
            })
            while (arr.length() > 50) arr.remove(0)
            file.writeText(arr.toString())
        }
    }

    fun getAddressStats(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt("addr_verified", 0) to prefs.getInt("addr_total", 0)
    }

    /** Record that a user marked an extracted address as incorrect. */
    fun recordAddressIncorrect(context: Context, address: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val total = prefs.getInt("addr_total", 0)
        prefs.edit()
            .putInt("addr_total", total + 1)
            .putInt("addr_incorrect", prefs.getInt("addr_incorrect", 0) + 1)
            .apply()
    }

    // ---------------------------------------------------------------
    // Per-item confirmation state persistence (by history ID)
    // ---------------------------------------------------------------

    fun isCodeConfirmed(ctx: Context, id: Long) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ci_${id}_code", false)
    fun setCodeConfirmed(ctx: Context, id: Long, v: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ci_${id}_code", v).apply()
    fun isSourceConfirmed(ctx: Context, id: Long) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ci_${id}_src", false)
    fun setSourceConfirmed(ctx: Context, id: Long, v: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ci_${id}_src", v).apply()
    fun isAddrConfirmed(ctx: Context, id: Long) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ci_${id}_addr", false)
    fun setAddrConfirmed(ctx: Context, id: Long, v: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ci_${id}_addr", v).apply()
    fun isCodeIncorrect(ctx: Context, id: Long) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ci_${id}_code_bad", false)
    fun setCodeIncorrect(ctx: Context, id: Long, v: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ci_${id}_code_bad", v).apply()
    fun isSourceIncorrect(ctx: Context, id: Long) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ci_${id}_src_bad", false)
    fun setSourceIncorrect(ctx: Context, id: Long, v: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ci_${id}_src_bad", v).apply()
    fun isAddrIncorrect(ctx: Context, id: Long) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ci_${id}_addr_bad", false)
    fun setAddrIncorrect(ctx: Context, id: Long, v: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ci_${id}_addr_bad", v).apply()

    // ---------------------------------------------------------------
    // Auto-apply: check suggestions and persist high-confidence patterns
    // ---------------------------------------------------------------

    data class LearnedRule(
        val regex: String,
        val type: String,       // "pickup_parcel" / "pickup_food"
        val label: String,
        val count: Int,
        val enabled: Boolean = true,
        val confidence: Float = 0.5f,
        val sampleCount: Int = 0,
        val lastUsedAt: Long = 0L,
        val decayed: Boolean = false   // B3: 长期未命中自动降级为「可选」而非强制应用
    )

    private const val KEY_LEARNED = "learned_rules"
    private const val KEY_LAST_AUTOAPPLY = "last_autoapply"
    /** B3: 多少毫秒未使用视为"衰减"，自动降级为可选规则（默认 21 天）。 */
    private const val DECAY_MS = 21L * 24 * 60 * 60 * 1000
    private const val AUTO_APPLY_THROTTLE_MS = 6L * 60 * 60 * 1000 // 6h
    private const val TOUCH_THROTTLE_MS = 60L * 1000 // B3 touch 节流：1 分钟内不重复全量写盘

    /** A1: 停用/启用某条已学规则。 */
    fun setRuleEnabled(context: Context, regex: String, enabled: Boolean) {
        val rules = getLearnedPatterns(context).map {
            if (it.regex == regex) it.copy(enabled = enabled) else it
        }
        saveLearnedPatterns(context, rules)
    }

    /** A1: 删除某条已学规则。 */
    fun deleteRule(context: Context, regex: String) {
        saveLearnedPatterns(context, getLearnedPatterns(context).filterNot { it.regex == regex })
    }

    /** B3: 一条规则被识别命中时调用，更新 lastUsedAt 并解除衰减降级。
     *  节流：距上次 touch 该规则 < 阈值则跳过，避免识别主循环每次命中都全量重写 learned_rules。 */
    fun touchRule(context: Context, regex: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stampKey = "touch_" + regex.hashCode()
        val now = System.currentTimeMillis()
        val last = prefs.getLong(stampKey, 0L)
        if (now - last < TOUCH_THROTTLE_MS) return
        prefs.edit().putLong(stampKey, now).apply()
        val rules = getLearnedPatterns(context).map {
            if (it.regex == regex) it.copy(lastUsedAt = now, decayed = false) else it
        }
        saveLearnedPatterns(context, rules)
    }

    private fun saveLearnedPatterns(context: Context, rules: List<LearnedRule>) {
        val arr = JSONArray()
        val now = System.currentTimeMillis()
        for (r in rules) {
            // B3: 衰减判断——已启用（非用户手动停用）且超期未用 → 降级为可选
            val decayed = r.enabled && r.decayed || (r.enabled && r.lastUsedAt > 0 && now - r.lastUsedAt > DECAY_MS && r.count <= 3)
            arr.put(JSONObject().apply {
                put("regex", r.regex)
                put("type", r.type)
                put("label", r.label)
                put("count", r.count)
                put("enabled", r.enabled)
                put("confidence", r.confidence.toDouble())
                put("sampleCount", r.sampleCount)
                put("lastUsedAt", if (r.lastUsedAt > 0) r.lastUsedAt else now)
                put("decayed", decayed)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LEARNED, arr.toString()).apply()
    }

    /** Check suggestions and auto-apply patterns with count ≥ minCount and confidence ≥ minConf. */
    fun autoApply(context: Context, minCount: Int = MIN_SUGGEST, minConfidence: Float = 0.5f): List<LearnedRule> {        val suggestions = getSuggestions(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = getLearnedPatterns(context).toMutableList()
        val existingRegexes = existing.map { it.regex }.toSet()

        val newRules = mutableListOf<LearnedRule>()
        for (s in suggestions) {
            if (s.count < minCount || s.confidence < minConfidence) continue
            if (s.proposedRegex in existingRegexes) continue

            // 加固：拒绝过度泛化的 token —— 含 'X'(任意字符) 的 token 会生成匹配任何文本的规则，
            // 极易误报（如 X-d4 会匹配 "A-1234" 也会匹配 "啊-1234"）。只采纳由明确字符类
            // （数字 d / 字母 L / 连字符 / 下划线 / 点 / 空格）构成的模式。
            if (s.tokenPattern.any { it != 'd' && it != 'L' && it != '-' && it != '_' && it != ' ' && it != '.' && !it.isDigit() }) {
                continue
            }

            // Guess type: letter+digit combos are usually parcel codes
            val type = if (s.label.contains("letter") || s.tokenPattern.any { it == 'L' } || s.tokenPattern.contains('-'))
                "pickup_parcel" else "pickup_food"

            val rule = LearnedRule(s.proposedRegex, type, s.label, s.count,
                confidence = s.confidence, sampleCount = s.count, lastUsedAt = System.currentTimeMillis())
            newRules.add(rule)
            existing.add(rule)
        }

        if (newRules.isNotEmpty()) {
            saveLearnedPatterns(context, existing)

            // Clear unmatched samples after successful learning
            clearUnmatched(context)
        }
        return newRules
    }

    /** 节流版 autoApply：距上次自动学习不足阈值则跳过，避免高频 IO（读文件+聚类+写规则）。 */
    private fun autoApplyThrottled(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_AUTOAPPLY, 0)
        if (now - last < AUTO_APPLY_THROTTLE_MS) return
        prefs.edit().putLong(KEY_LAST_AUTOAPPLY, now).apply()
        autoApply(context)
    }

    fun getLearnedPatterns(context: Context): List<LearnedRule> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_LEARNED, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                LearnedRule(
                    obj.getString("regex"),
                    obj.getString("type"),
                    obj.getString("label"),
                    obj.optInt("count", 0),
                    enabled = obj.optBoolean("enabled", true),
                    confidence = obj.optDouble("confidence", 0.5).toFloat(),
                    sampleCount = obj.optInt("sampleCount", 0),
                    lastUsedAt = obj.optLong("lastUsedAt", 0L),
                    decayed = obj.optBoolean("decayed", false)
                )
            }
        } catch (_: Exception) { emptyList() }
    }
}