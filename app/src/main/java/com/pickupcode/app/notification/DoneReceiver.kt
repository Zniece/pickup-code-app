package com.pickupcode.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pickupcode.app.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** 通知栏「已取」按钮：标记 DB 记录为已完成并消除通知 */
class DoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val historyId = intent.getLongExtra("history_id", -1)
        val notificationId = intent.getIntExtra("notification_id", -1)

        val db = AppDatabase.getInstance(context)
        runBlocking {
            if (historyId > 0) {
                db.codeHistoryDao().markDone(historyId)
            }
        }

        if (notificationId != -1) {
            CodeNotificationManager.dismissById(context, notificationId)
        }
    }
}
