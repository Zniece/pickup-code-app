package com.pickupcode.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import com.pickupcode.app.data.AppDatabase
import com.pickupcode.app.data.CodeHistory
import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.learner.CommonStationStore
import com.pickupcode.app.notification.CodeNotificationManager
import com.pickupcode.app.ocr.OCREngine
import com.pickupcode.app.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 短信取件码自动识别（借鉴反编译 App 的 SmsReceiver）。
 *
 * 输入源补充通道：取件/取餐短信（菜鸟、丰巢、妈妈驿站等几乎都会发短信）到达时，
 * 即使无障碍服务没开、通知栏没弹，也能自动提取取件码并入库通知。
 *
 * 依赖 READ_SMS 权限（可选开关）。处理流程：
 * 1. 校验开关 + 去重节流（同内容在阈值内不重复处理）
 * 2. 解析短信正文为 OCR 文本行 → CodeExtractor 识别码 + 地址
 * 3. 金融/支付噪音拦截
 * 4. 入库（与无障碍/分享共用 DAO）+ 通知 + 常用站点学习
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        val extras = intent.getExtras() ?: return
        val pdus = extras.get("pdus") as? Array<*> ?: return
        if (pdus.isEmpty()) return

        // 拼出第一条完整短信（多段 PDU 合体）
        val messages = pdus.mapNotNull { pdu ->
            try {
                SmsMessage.createFromPdu(pdu as? ByteArray, extras.getString("format"))
            } catch (_: Exception) { null }
        }
        if (messages.isEmpty()) return
        var body = messages.joinToString("") { it.messageBody ?: "" }.trim()
        if (body.isEmpty()) return
        // 去掉发送者的前后重复标题（如 【菜鸟驿站】...【菜鸟驿站】），仅保留正文主体
        val sender = messages.firstOrNull()?.originatingAddress ?: ""
        val rawSnippet = "[短信] $sender | $body"

        // 节流：同内容 30s 内不重复处理
        val now = System.currentTimeMillis()
        if (now - lastProcessTime < THROTTLE_MS && lastBody == body) {
            Log.d(TAG, "短信节流跳过（同内容重复）")
            return
        }
        lastProcessTime = now
        lastBody = body

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = AppPreferences.observe(context).first()
                if (!settings.enableSmsReceive) return@launch
                if (!settings.enableParcelCodes && !settings.enableFoodCodes) return@launch

                // 金融/支付噪音拦截（短信里银行/支付通知的数字极易被当取件码）
                if (CodeExtractor.isFinancialNoise(body)) {
                    Log.d(TAG, "短信为金融/支付类，跳过")
                    return@launch
                }

                val lines = body.lines()
                    .map { OCREngine.TextLine(text = it.trim(), boundingBox = null, confidence = 1.0f) }
                    .filter { it.text.isNotBlank() }
                if (lines.isEmpty()) return@launch

                val allText = lines.joinToString(" ") { it.text }
                val results = CodeExtractor.extract(lines, context = context, source = "sms")
                val parcel = results.firstOrNull { it.type == CodeExtractor.CodeType.pickup_parcel }
                val food = results.firstOrNull { it.type == CodeExtractor.CodeType.pickup_food }

                // 只认取件/取餐码；忽略券码
                val candidate = when {
                    parcel != null -> parcel
                    food != null -> food
                    else -> null
                }
                if (candidate == null) {
                    Log.d(TAG, "短信无取件码，跳过")
                    return@launch
                }

                val address = if (candidate.type == CodeExtractor.CodeType.pickup_parcel)
                    CodeExtractor.extractAddress(lines, allText, context) else ""
                val cabinet = if (candidate.type == CodeExtractor.CodeType.pickup_parcel)
                    CodeExtractor.extractCabinetNumber(lines, allText) else ""

                val db = AppDatabase.getInstance(context)
                val dao = db.codeHistoryDao()
                // 短信路径用 saveOrUpdate：同 code+type 更新而非新增（短信来源稳定，避免刷屏重复）
                val save = dao.saveOrUpdate(CodeHistory(
                    code = candidate.code,
                    type = candidate.type.name,
                    source = candidate.source,
                    rawTextSnippet = rawSnippet,
                    pickupAddress = address,
                    cabinetNumber = cabinet,
                    timestamp = now
                ))

                // 常用站点学习
                if (candidate.type == CodeExtractor.CodeType.pickup_parcel && address.isNotBlank()) {
                    CommonStationStore.recordCode(context, address, body)
                }

                if (save.existed) {
                    val dupCount = dao.countDuplicateGroups()
                    CodeNotificationManager.showDuplicate(
                        context, candidate.code, candidate.type, candidate.source, save.id, dupCount
                    )
                } else {
                    CodeNotificationManager.show(context, candidate.code, candidate.type, candidate.source, save.id)
                }
                Log.d(TAG, "短信识别入库: ${candidate.code} (${candidate.type.name}) @ $address")
            } catch (e: Exception) {
                Log.e(TAG, "短信识别失败: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SmsReceiver"
        const val THROTTLE_MS = 30_000L
        @Volatile var lastProcessTime = 0L
        @Volatile var lastBody = ""
    }
}
