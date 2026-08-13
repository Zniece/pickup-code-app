package com.pickupcode.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import com.pickupcode.app.data.AppDatabase
import com.pickupcode.app.extractor.AddressExtractor
import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.ocr.OCREngine
import com.pickupcode.app.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 短信取件码自动识别（参考同类产品实现）。
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
        val displaySender = if (sender.length > 4) "***${sender.takeLast(4)}" else sender
        val rawSnippet = "[短信] $displaySender | $body"

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
                // H5: goAsync 必须限时完成（广播接收器超时会被系统回收），8s 未完成即放弃，finally 仍会 finish
                withTimeoutOrNull(8000) {
                    val settings = AppPreferences.observe(context).first()
                    if (!settings.enableSmsReceive) return@withTimeoutOrNull
                    if (!settings.enableParcelCodes && !settings.enableFoodCodes) return@withTimeoutOrNull

                    // 金融/支付噪音拦截（短信里银行/支付通知的数字极易被当取件码）
                    if (CodeExtractor.isFinancialNoise(body)) {
                        Log.d(TAG, "短信为金融/支付类，跳过")
                        return@withTimeoutOrNull
                    }

                    val lines = body.lines()
                        .map { OCREngine.TextLine(text = it.trim(), boundingBox = null, confidence = 1.0f) }
                        .filter { it.text.isNotBlank() }
                    if (lines.isEmpty()) return@withTimeoutOrNull

                    val allText = lines.joinToString(" ") { it.text }
                    val results = CodeExtractor.extract(lines, context = context, source = "sms")
                    val allResults = results.filter {
                        it.confidence >= settings.confidenceThreshold
                    }
                    if (allResults.isEmpty()) {
                        Log.d(TAG, "短信无取件码，跳过")
                        return@withTimeoutOrNull
                    }

                    // 全屏地址（兜底用，各码优先取自己窗口内的地址）
                    val fullAddress = AddressExtractor.extractAddress(lines, allText, context)
                    val db = AppDatabase.getInstance(context)
                    val repo = db.repository

                    val saved = RecognitionPipeline.finalize(
                        context = context,
                        allResults = allResults.map { it.code to it.type },
                        codeSources = allResults.associate { it.code to it.source },
                        lines = lines,
                        allText = allText,
                        fullAddress = fullAddress,
                        rawSnippet = rawSnippet,
                        timestamp = now,
                        repo = repo
                    )
                    for (s in saved) {
                        RecognitionPipeline.notifySaved(context, { repo.countDuplicateGroups() },
                            s.code, s.type, s.source, s.id, s.existed)
                        RecognitionPipeline.logSaved(TAG, s.code, s.type, s.source, s.address, s.existed)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 协程取消必须重抛，不能吞
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
