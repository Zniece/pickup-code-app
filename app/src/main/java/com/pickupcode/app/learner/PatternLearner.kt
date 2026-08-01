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

    /** Record that the extractor matched a code using this pattern.
     *  This is NOT a correctness signal — just pattern usage tracking. */
    fun recordAttempt(context: Context, patternId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_TOTAL, prefs.getInt(KEY_TOTAL, 0) + 1)
            .putInt(KEY_ATTEMPTS, prefs.getInt(KEY_ATTEMPTS, 0) + 1)
            .putInt(KEY_PAT_PREFIX + patternId, prefs.getInt(KEY_PAT_PREFIX + patternId, 0) + 1)
            .apply()
    }

    /** Record that the extractor found nothing in the OCR output.
     *  仅轻量记录；autoApply（读文件+聚类+写规则）通过低频节流触发，避免每次 miss 都做重 IO。 */
    fun recordMiss(context: Context, rawText: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_TOTAL, prefs.getInt(KEY_TOTAL, 0) + 1)
            .putInt(KEY_MISSES, prefs.getInt(KEY_MISSES, 0) + 1)
            .apply()
        appendUnmatched(context, rawText)
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
            val tok = tokenize(s.optString("text", ""))
            if (tok.length >= 2) {
                clustered.getOrPut(tok) { mutableListOf() }.add(s.optString("text", ""))
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

    private fun appendUnmatched(context: Context, rawText: String) {
        if (rawText.isBlank()) return
        val file = File(context.filesDir, "unmatched_samples.json")
        val arr = if (file.exists()) {
            try { JSONArray(file.readText()) } catch (_: Exception) { JSONArray() }
        } else JSONArray()

        // Keep only recent + relevant text
        val snippet = rawText.take(300)
        arr.put(JSONObject().apply {
            put("text", snippet)
            put("ts", System.currentTimeMillis() / 1000)
        })

        // Trim to max
        while (arr.length() > MAX_UNMATCHED) arr.remove(0)
        file.writeText(arr.toString())
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

        // Store last verified address as positive example for extraction tuning
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
        val count: Int
    )

    private const val KEY_LEARNED = "learned_rules"
    private const val KEY_LAST_AUTOAPPLY = "last_autoapply"
    private const val AUTP_APPLY_THROTTLE_MS = 6L * 60 * 60 * 1000 // 6h

    /** Check suggestions and auto-apply patterns with count ≥ minCount and confidence ≥ minConf. */
    fun autoApply(context: Context, minCount: Int = MIN_SUGGEST, minConfidence: Float = 0.5f): List<LearnedRule> {        val suggestions = getSuggestions(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = getLearnedPatterns(context).toMutableList()
        val existingRegexes = existing.map { it.regex }.toSet()

        val newRules = mutableListOf<LearnedRule>()
        for (s in suggestions) {
            if (s.count < minCount || s.confidence < minConfidence) continue
            if (s.proposedRegex in existingRegexes) continue

            // Guess type: letter+digit combos are usually parcel codes
            val type = if (s.label.contains("letter") || s.tokenPattern.any { it == 'L' } || s.tokenPattern.contains('-'))
                "pickup_parcel" else "pickup_food"

            val rule = LearnedRule(s.proposedRegex, type, s.label, s.count)
            newRules.add(rule)
            existing.add(rule)
        }

        if (newRules.isNotEmpty()) {
            val arr = JSONArray()
            for (r in existing) {
                arr.put(JSONObject().apply {
                    put("regex", r.regex)
                    put("type", r.type)
                    put("label", r.label)
                    put("count", r.count)
                })
            }
            prefs.edit().putString(KEY_LEARNED, arr.toString()).apply()

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
        if (now - last < AUTP_APPLY_THROTTLE_MS) return
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
                    obj.optInt("count", 0)
                )
            }
        } catch (_: Exception) { emptyList() }
    }
}