package com.pickupcode.app.service

import android.content.Context
import android.util.Log
import com.pickupcode.app.BuildConfig
import com.pickupcode.app.geocoder.GeocoderVerifier
import com.pickupcode.app.kuaidi100.Kuaidi100Verifier

/**
 * 识别后置验证（PostVerifier）：抽取无障碍服务（PickupCodeAccessibilityService）与
 * 分享接收（ShareReceiver）路径共用的「地图地址验证 / 快递100 反向验证」。
 *
 * 两处共用相同的验证调用与日志，差异仅在验证成功后的处理——用回调参数化
 * （地图）或返回结果由调用方决定回填策略（快递100）。
 */
object PostVerifier {

    private const val TAG = "PostVerifier"

    /** 地图地址验证。成功时回调 [onVerified]（confidence, formattedAddress）；返回是否验证通过。 */
    suspend fun verifyMap(
        context: Context,
        address: String,
        amapApiKey: String?,
        onVerified: suspend (confidence: Float, formattedAddress: String?) -> Unit
    ): Boolean {
        val result = GeocoderVerifier.verify(context, address, amapApiKey = amapApiKey)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Map verify: verified=${result.verified}, confidence=${result.confidence}, provider=${result.provider}, address=$address")
        }
        if (result.verified) {
            onVerified(result.confidence, result.formattedAddress)
        }
        return result.verified
    }

    /** 快递100 反向验证。成功且命中码值返回 [Kuaidi100Verifier.KuaidiResult]，否则返回 null。 */
    suspend fun verifyKuaidi100(
        context: Context,
        key: String,
        trackingNum: String,
        ocrCodes: List<String>
    ): Kuaidi100Verifier.KuaidiResult? {
        val res = Kuaidi100Verifier.query(key, trackingNum, Kuaidi100Verifier.guessCourierCode(trackingNum))
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Kuaidi100 verify: success=${res.success} code=${res.pickUpCode} address=${res.pickUpAddress} err=${res.errorMsg}")
            if (res.success && res.pickUpCode != null) {
                if (ocrCodes.contains(res.pickUpCode)) {
                    Log.d(TAG, "Kuaidi100 confirm: OCR码 ${res.pickUpCode} 与 API 一致 ✓")
                } else {
                    Log.d(TAG, "Kuaidi100 mismatch: OCR=${ocrCodes}, API=${res.pickUpCode}")
                }
            }
        }
        return if (res.success && res.pickUpCode != null) res else null
    }
}
