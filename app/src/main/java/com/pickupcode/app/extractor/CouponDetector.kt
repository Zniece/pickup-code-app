package com.pickupcode.app.extractor

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * 券码检测器：用 ML Kit Barcode（bundled）检测 + 解码图片中的二维码/条码。
 * 返回解码出的结果列表；空列表 = 未检测到。
 * 支持两条路径（无障碍截图 / 分享图片）共用。
 */
object CouponDetector {

    private const val TAG = "CouponDetector"

    // 只认二维码：二维码有三定位角结构，普通数字/文本不会被误识别为二维码，彻底避免"不认数字"的误报
    private val scanner: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    data class CouponResult(
        val rawValue: String?   // 解码内容（码值）
    )

    /** 检测并解码 bitmap 中的二维码/条码，返回解码结果；异常或未检测到返回空列表。 */
    suspend fun detect(bitmap: Bitmap): List<CouponResult> = withContext(Dispatchers.Default) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val barcodes = scanner.process(image).await()
            barcodes
                .filter { !it.rawValue.isNullOrBlank() }
                .map { CouponResult(rawValue = it.rawValue) }
        } catch (e: Exception) {
            Log.e(TAG, "条码检测失败: ${e.message}")
            emptyList()
        }
    }
}
