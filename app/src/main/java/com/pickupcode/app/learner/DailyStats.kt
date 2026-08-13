package com.pickupcode.app.learner

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 每日命中率统计（自 PatternLearner 拆出，2026-08-13）。
 * 职责：记录每天的 {total, hits, misses} 序列，供统计面板命中率曲线。
 * 存储：SharedPreferences "pattern_learner"（与 PatternLearner 共用文件名，保证拆分迁移数据不丢）。
 */
object DailyStats {

    private const val PREFS = "pattern_learner"
    private const val KEY_DAY_STATS = "day_stats"
    private const val MAX_DAY_STATS = 30

    private val dayStatsLock = Any()

    data class DayStat(val date: String, val total: Int, val hits: Int, val misses: Int)

    private fun todayKey(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return fmt.format(java.util.Date())
    }

    /** 记录当天的 {total, hits, misses}，供命中率曲线。 */
    fun recordDay(context: Context, isHit: Boolean, isMiss: Boolean) {
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
                } catch (e: Exception) { Log.w("DailyStats", "日统计数据JSON损坏，已重置", e) }
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

    /** 每日命中率序列：按日期升序的 {date, total, hits, misses}。 */
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
}
