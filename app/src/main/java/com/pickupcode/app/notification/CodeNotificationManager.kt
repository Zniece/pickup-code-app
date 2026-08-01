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
    private const val CHANNEL_COUPON = "pickup_coupon"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
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
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_COUPON, "券码", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "二维码/条码券码提醒"
                setShowBadge(true)
            }
        )
    }

    private fun safeId(code: String): Int = code.hashCode() and 0x7fffffff

    private data class TypeStyle(val channelId: String, val iconLabel: String, val title: String)

    private fun typeStyle(type: CodeExtractor.CodeType): TypeStyle = when (type) {
        CodeExtractor.CodeType.pickup_parcel -> TypeStyle(CHANNEL_PARCEL, "\uD83D\uDCE6", "取件码")
        CodeExtractor.CodeType.pickup_food -> TypeStyle(CHANNEL_FOOD, "\uD83E\uDD64", "取餐码")
        CodeExtractor.CodeType.coupon -> TypeStyle(CHANNEL_COUPON, "\uD83C\uDF9F\uFE0F", "券码")
    }

    fun show(context: Context, code: String, type: CodeExtractor.CodeType, source: String, historyId: Long? = null) {
        // Android 13+ 需运行时通知权限，未授予则不发送（静默忽略，避免异常）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val style = typeStyle(type)
        val channelId = style.channelId
        val iconLabel = style.iconLabel
        val title = style.title

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
                PendingIntent.getBroadcast(context, safeId(code) + 1,
                    Intent(context, DoneReceiver::class.java).apply {
                        putExtra("history_id", historyId ?: -1)
                        putExtra("notification_id", safeId(code))
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "忽略",
                PendingIntent.getBroadcast(context, safeId(code),
                    Intent(context, NotificationDismissReceiver::class.java).apply {
                        putExtra("notification_id", safeId(code))
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(safeId(code), notification)
    }

    fun dismiss(context: Context, code: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(safeId(code))
    }

    fun dismissById(context: Context, id: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(id)
    }

    /** Show notification for a duplicate code — informs user there are now ≥2 records for this code. */
    fun showDuplicate(context: Context, code: String, type: CodeExtractor.CodeType, source: String, historyId: Long, dupGroupCount: Int) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val style = typeStyle(type)
        val channelId = style.channelId
        val iconLabel = style.iconLabel

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("show_dedup", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, safeId(code), intent,
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

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify("dup_$code".hashCode() and 0x7fffffff, notification)
    }
}
