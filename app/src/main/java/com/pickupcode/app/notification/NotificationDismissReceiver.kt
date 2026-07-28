package com.pickupcode.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知栏「忽略」按钮的接收器 */
class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra("notification_id", -1)
        if (notificationId != -1) {
            CodeNotificationManager.dismissById(context, notificationId)
        }
    }
}
