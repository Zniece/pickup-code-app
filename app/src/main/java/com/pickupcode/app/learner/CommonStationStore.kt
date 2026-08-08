package com.pickupcode.app.learner

import android.content.Context
import org.json.JSONArray

/**
 * 常用站点缓存（借鉴反编译 App 的 setCommonStations + 缓存机制）。
 *
 * 用途：用户经常取件的驿站/快递柜/取件点，地址识别时优先匹配，提升多驿站/噪音场景的取件地址精度。
 * 与 [PatternLearner] 同风格：SharedPreferences 轻量 JSON 存储，无第三方依赖。
 *
 * 写入时机：每次成功保存取件记录时调用 [recordCode]（带地址），自动累计站点出现次数。
 * 读取时机：地址识别时调用 [getCommonStations] 拿 Top-N 常用站点做优先匹配。
 * 存储上限：[MAX_ENTRIES] 条，按次数排序，超出裁剪。
 */
object CommonStationStore {

    private const val PREFS = "common_stations"
    private const val KEY_STATIONS = "stations"

    /** 存储上限：只保留站点使用频次 Top-N 的文案，防止无限膨胀。 */
    private const val MAX_ENTRIES = 60

    data class StationEntry(val name: String, val count: Int)

    /** 从取件地址/站名/原文里抠候选站点名：优先取「xx驿站/xx快递柜/xx店/xx代收点/xx柜」这类站名片段。 */
    private fun extractStationName(text: String): String? {
        if (text.isBlank()) return null
        // 常见站点后缀 → 剥出前面的站名（如 育新路北段菜鸟驿站 → 育新路北段菜鸟驿站）
        val suffixes = listOf("菜鸟驿站", "妈妈驿站", "兔喜生活", "快递柜", "丰巢", "代收点", "自提点", "服务站", "驿站")
        for (sfx in suffixes) {
            val idx = text.indexOf(sfx)
            if (idx >= 0) {
                // 从该词往前取最多 12 个字符作为站名（避免把整串地址都当站名）
                val start = (idx - 12).coerceAtLeast(0)
                var s = text.substring(start, idx + sfx.length).trim()
                // 截到标点/括号边界
                val cut = s.indexOfFirst { it in "，,。；;：:（）()【】|｜ " }
                if (cut > 0) s = s.substring(0, cut).trim()
                if (s.length in 2..20) return s
            }
        }
        // 兜底：整串取件地址里太不确定，不硬造站名，返回 null
        return null
    }

    /** 记录一次识别：把取件地址/原文中的站点名累计出现次数。地址为空时跳过（不学噪声）。 */
    // Medium-1: read-modify-write SharedPreferences 加 @Synchronized，防多入口（分享/无障碍/短信）并发丢计数
    @Synchronized
    fun recordCode(context: Context, address: String, rawText: String = "") {
        val name = extractStationName(address.ifBlank { rawText }) ?: return
        val cur = loadInternal(context)
        val next = LinkedHashMap<String, Int>()
        // 保留出现次数 >1 的既有条目 + 本次 +1
        for ((k, v) in cur) {
            if (k == name) continue
            if (v > 0) next[k] = v
        }
        val newCount = (cur[name] ?: 0) + 1
        next[name] = newCount
        // 裁剪：保留次数 Top-N，但保底保留本次刚写入的 name（避免低频站点反复丢失）
        val sorted = next.entries.sortedByDescending { it.value }.take(MAX_ENTRIES)
        val finalEntries = if (sorted.any { it.key == name }) sorted
        else sorted.dropLast(1) + mapOf(name to newCount).entries.first()
        val arr = JSONArray()
        for ((k, v) in finalEntries) {
            arr.put(JSONArray().put(k).put(v))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATIONS, arr.toString()).apply()
    }

    /** 读取常用站点列表（按使用次数降序），供地址识别优先匹配。 */
    fun getCommonStations(context: Context): List<StationEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATIONS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONArray(i)
                    val name = e.optString(0, "")
                    val count = e.optInt(1, 0)
                    if (name.isNotBlank() && count > 0) add(StationEntry(name, count))
                }
            }.sortedByDescending { it.count }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 清空（调试/设置用）。 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** 内部读取原始 map，避免每次解析。 */
    private fun loadInternal(context: Context): Map<String, Int> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATIONS, null) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            buildMap {
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONArray(i)
                    put(e.optString(0, ""), e.optInt(1, 0))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
