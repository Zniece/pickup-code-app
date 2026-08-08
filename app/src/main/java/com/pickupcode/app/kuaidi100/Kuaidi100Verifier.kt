package com.pickupcode.app.kuaidi100

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Kuaidi100Verifier {

    private const val TAG = "Kuaidi100Verifier"
    private const val API_URL = "https://api.kuaidi100.com/pickupcode/query"

    data class KuaidiResult(
        val success: Boolean,
        val pickUpCode: String?,
        val pickUpStation: String?,
        val pickUpAddress: String?,
        val errorMsg: String?
    )

    /**
     * Query pickup code by tracking number + courier code.
     * Requires a valid kuaidi100 API key.
     *
     * @param apiKey kuaidi100 customer key
     * @param trackingNum courier tracking number
     * @param courierCode courier company code (e.g. "jitu", "zhongtong")
     */
    suspend fun query(
        apiKey: String,
        trackingNum: String,
        courierCode: String? = null
    ): KuaidiResult = withContext(Dispatchers.IO) {
        try {
            val params = buildString {
                append("key=").append(apiKey)
                append("&num=").append(URLEncoder.encode(trackingNum, "UTF-8"))
                if (!courierCode.isNullOrBlank()) {
                    append("&com=").append(URLEncoder.encode(courierCode, "UTF-8"))
                }
            }
            val url = URL("$API_URL?$params")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"

                if (conn.responseCode != 200) {
                    Log.w(TAG, "API returned HTTP ${conn.responseCode}")
                    return@withContext KuaidiResult(false, null, null, null, "HTTP ${conn.responseCode}")
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)

                val code = json.optInt("returnCode", -1)
                if (code != 200) {
                    return@withContext KuaidiResult(false, null, null, null, json.optString("message", "API error"))
                }

                val data = json.optJSONObject("data") ?: return@withContext KuaidiResult(false, null, null, null, "No data")
                // B5: optString(key, null) 在 key 缺失时返回字面量 "null"（非 null），须用 isNull 判缺失
                fun optNullable(key: String): String? =
                    if (data.isNull(key)) null else data.optString(key).takeIf { it.isNotBlank() }
                KuaidiResult(
                    success = true,
                    pickUpCode = optNullable("pickUpCode"),
                    pickUpStation = optNullable("pickUpStation"),
                    pickUpAddress = optNullable("pickUpAddress"),
                    errorMsg = null
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Query failed: ${e.message}")
            KuaidiResult(false, null, null, null, e.message)
        }
    }

    /**
     * Attempt to auto-detect courier code from tracking number prefix.
     * 品牌识别统一委托给 CodeExtractor（单一来源），此处仅做中文品牌 → 快递100 com 码映射。
     * 快递100 com 码参考其官方对照表。
     */
    fun guessCourierCode(trackingNum: String): String? {
        val brand = com.pickupcode.app.extractor.BrandResolver.guessOrderBrand(trackingNum) ?: return null
        return BRAND_TO_KUAIDI100[brand]
    }

    private val BRAND_TO_KUAIDI100 = mapOf(
        "极兔" to "jitu",
        "京东物流" to "jd",
        "顺丰" to "shunfeng",
        "圆通" to "yuantong",
        "韵达" to "yunda",
        "中通" to "zhongtong",
        "申通" to "shentong",
        "EMS" to "ems",
        "邮政" to "youzheng",
        "德邦" to "deppon"
    )
}