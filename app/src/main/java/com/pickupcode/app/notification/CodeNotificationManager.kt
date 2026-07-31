package com.pickupcode.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pickupcode.app.MainActivity
import com.pickupcode.app.extractor.CodeExtractor

object CodeNotificationManager {

    private const val CHANNEL_FOOD = "pickup_food"
    private const val CHANNEL_PARCEL = "pickup_parcel"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_FOOD, "取餐码", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "取餐码提醒"
                setShowBadge(true)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PARCEL, "取件码", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "快递取件码提醒"
                setShowBadge(true)
            }
        )
    }

    fun show(context: Context, code: String, type: CodeExtractor.CodeType, source: String, historyId: Long? = null) {
        val channelId: String
        val iconLabel: String
        val title: String
        when (type) {
            CodeExtractor.CodeType.pickup_parcel -> {
                channelId = CHANNEL_PARCEL; iconLabel = "\uD83D\uDCE6"; title = "取件码"
            }
            CodeExtractor.CodeType.pickup_food -> {
                channelId = CHANNEL_FOOD; iconLabel = "\uD83E\uDD64"; title = "取餐码"
            }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$iconLabel $title")
            .setContentText("$source  —  $code")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$iconLabel $source\n$title: $code"))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "已取",
                PendingIntent.getBroadcast(context, code.hashCode() + 1,
                    Intent(context, DoneReceiver::class.java).apply {
                        putExtra("history_id", historyId ?: -1)
                        putExtra("notification_id", code.hashCode())
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "忽略",
                PendingIntent.getBroadcast(context, code.hashCode(),
                    Intent(context, NotificationDismissReceiver::class.java).apply {
                        putExtra("notification_id", code.hashCode())
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(code.hashCode(), notification)
    }

    fun dismiss(context: Context, code: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(code.hashCode())
    }

    fun dismissById(context: Context, id: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(id)
    }

    /** Show notification for a duplicate code — informs user there are now ≥2 records for this code. */
    fun showDuplicate(context: Context, code: String, type: CodeExtractor.CodeType, source: String, historyId: Long, dupGroupCount: Int) {
        val channelId = when (type) {
            CodeExtractor.CodeType.pickup_parcel -> CHANNEL_PARCEL
            CodeExtractor.CodeType.pickup_food -> CHANNEL_FOOD
        }
        val iconLabel = when (type) {
            CodeExtractor.CodeType.pickup_parcel -> "\uD83D\uDCE6"
            CodeExtractor.CodeType.pickup_food -> "\uD83E\uDD64"
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("show_dedup", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, code.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$iconLabel $code 再次出现")
            .setContentText("$source · 点击整理去重（共 ${dupGroupCount} 组重复）")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify("dup_$code".hashCode(), notification)
    }
}
