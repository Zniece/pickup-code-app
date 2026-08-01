package com.pickupcode.app.extractor

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 提取器：通过 OpenAI 兼容 API 从屏幕文字中提取取餐码/取件码
 */
object AIExtractor {

    data class AIResult(
        val code: String,
        val type: CodeExtractor.CodeType,
        val source: String
    )

    /** 提取结果：results 为识别到的码；error 非空表示本次调用失败（网络/Key/解析），用于上层反馈 */
    data class AIExtractResult(
        val results: List<AIResult> = emptyList(),
        val error: String? = null
    )

    private val SYSTEM_PROMPT = """
你是一个取餐码/取件码识别助手。用户会发来一段手机屏幕上的文字，你需要从中提取所有取餐码和取件码。

请用纯JSON数组格式回复，不要包含markdown标记。每个元素的结构：
{"code":"码值","type":"pickup_food或pickup_parcel","source":"品牌/驿站名"}

规则：
- type: 食物取餐码用pickup_food，快递取件码用pickup_parcel
- code: 只提取码值本身，如"229"、"A-356"、"10-2-7507"
- source: 品牌名如"瑞幸""肯德基""菜鸟驿站""丰巢"等，找不到写"unknown"
- 如果有多个取件码/取餐码，全部列出来
- 如果没有任何取餐码或取件码，回复空数组 []
""".trimIndent()

    suspend fun extract(
        text: String,
        apiKey: String,
        apiBaseUrl: String = "https://api.openai.com/v1",
        model: String = "gpt-4o-mini"
    ): AIExtractResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("${apiBaseUrl.trimEnd('/')}/chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true

            val body = JSONObject().apply {
                put("model", model)
                put("temperature", 0.0)
                put("max_tokens", 500)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", SYSTEM_PROMPT)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", text)
                    })
                })
            }

            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode != 200) {
                val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                return@withContext AIExtractResult(error = "HTTP ${conn.responseCode}: ${errBody?.take(120) ?: ""}".trim())
            }

            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = JSONObject(response)
            val content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val arr = JSONArray(content)
            val results = mutableListOf<AIResult>()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val code = r.optString("code", "").trim()
                if (code.isBlank()) continue
                // 格式白名单校验：AI 结果不比正则可靠，只接受合法取餐/取件码格式（复用 CodeExtractor 规则）
                if (!CodeExtractor.isValidPickupCode(code)) continue
                val typeStr = r.optString("type", "pickup_parcel")
                results.add(AIResult(
                    code = code,
                    type = if (typeStr == "pickup_food") CodeExtractor.CodeType.pickup_food
                           else CodeExtractor.CodeType.pickup_parcel,
                    source = r.optString("source", "unknown").ifBlank { "unknown" }
                ))
            }
            AIExtractResult(results = results)
        } catch (e: Exception) {
            Log.e("AIExtractor", "AI识别异常", e)
            AIExtractResult(error = e.message ?: "AI调用失败")
        }
    }
}
