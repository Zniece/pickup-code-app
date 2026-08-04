package com.pickupcode.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pickupcode.app.extractor.CodeExtractor

/** C3: 稍后提醒 —— 收到 AlarmManager 定时广播后，重新推一条取件/取餐提醒通知。 */
class RemindReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra("remind_code") ?: return
        val typeName = intent.getStringExtra("remind_type") ?: return
        val source = intent.getStringExtra("remind_source").orEmpty()
        val type = runCatching { CodeExtractor.CodeType.valueOf(typeName) }
            .getOrDefault(CodeExtractor.CodeType.pickup_parcel)
        CodeNotificationManager.showReminder(context, code, type, source)
    }
}
