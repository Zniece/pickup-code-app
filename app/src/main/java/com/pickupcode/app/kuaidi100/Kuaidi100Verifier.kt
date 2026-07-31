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
            KuaidiResult(
                success = true,
                pickUpCode = data.optString("pickUpCode", null).takeIf { it.isNotBlank() },
                pickUpStation = data.optString("pickUpStation", null).takeIf { it.isNotBlank() },
                pickUpAddress = data.optString("pickUpAddress", null).takeIf { it.isNotBlank() },
                errorMsg = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Query failed: ${e.message}")
            KuaidiResult(false, null, null, null, e.message)
        }
    }

    /**
     * Attempt to auto-detect courier code from tracking number prefix.
     * Common prefixes used by kuaidi100.
     */
    fun guessCourierCode(trackingNum: String): String? {
        return when {
            trackingNum.startsWith("JT") || trackingNum.startsWith("jt") -> "jitu"
            trackingNum.startsWith("SF") -> "shunfeng"
            trackingNum.length == 15 && trackingNum.all { it.isDigit() } -> "zhongtong"
            trackingNum.startsWith("YT") -> "yuantong"
            trackingNum.startsWith("77") || trackingNum.startsWith("77") -> "shentong"
            trackingNum.length == 13 && trackingNum.all { it.isDigit() } -> "yunda"
            trackingNum.length == 13 -> "ems"
            else -> null
        }
    }
}