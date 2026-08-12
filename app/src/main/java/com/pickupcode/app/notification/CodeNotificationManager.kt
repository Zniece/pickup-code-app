package com.pickupcode.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.AlarmManager
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

    /** RESULT_NOTIFY_ID（结果提示通知 0x7FFFFF00，见 PickupCodeAccessibilityService）保留段起点：递增 ID 池恒低于该值，绝不与其冲突。 */
    private const val RESERVED_NOTIFY_ID = 0x7FFFFF00

    /** Medium-3: 确定性递增通知 id 池，替代 hashCode——不同取件码 hashCode 碰撞会导致通知互相覆盖。 */
    private val idCounter = java.util.concurrent.atomic.AtomicInteger(1)

    private fun nextNotifyId(): Int =
        (idCounter.getAndIncrement() and 0x7fffffff) % RESERVED_NOTIFY_ID

    /** 稳定请求码/提醒 id：基于 code+type 复合，减少短码 hashCode 碰撞，并校正非负（保留给 PendingIntent 请求码与提醒通知 ID 空间）。 */
    private fun safeId(type: CodeExtractor.CodeType, code: String): Int =
        ("$type:$code".hashCode() and 0x7fffffff)

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

        val nid = nextNotifyId()
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
                PendingIntent.getBroadcast(context, (nid + 1) and 0x7fffffff,
                    Intent(context, DoneReceiver::class.java).apply {
                        putExtra("history_id", historyId ?: -1)
                        putExtra("notification_id", nid)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "忽略",
                PendingIntent.getBroadcast(context, nid,
                    Intent(context, NotificationDismissReceiver::class.java).apply {
                        putExtra("notification_id", nid)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(nid, notification)
    }

    fun dismiss(context: Context, type: CodeExtractor.CodeType, code: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(safeId(type, code))
    }

    fun dismissById(context: Context, id: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(id)
    }

    // ---------------------------------------------------------------
    // C3: 稍后提醒 —— 用 AlarmManager 定时再弹一条取件/取餐提醒
    // ---------------------------------------------------------------

    /** 触发用的 extra key（与 RemindReceiver 共用）。 */
    private const val EXTRA_REMIND_CODE = "remind_code"
    private const val EXTRA_REMIND_TYPE = "remind_type"
    private const val EXTRA_REMIND_SOURCE = "remind_source"

    /** 稍后提醒：delayMs 毫秒后（默认 1 小时）重新推一条提醒通知。 */
    fun remindLater(context: Context, code: String, type: CodeExtractor.CodeType,
                    source: String, delayMs: Long = 60L * 60 * 1000) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, RemindReceiver::class.java).apply {
            putExtra(EXTRA_REMIND_CODE, code)
            putExtra(EXTRA_REMIND_TYPE, type.name)
            putExtra(EXTRA_REMIND_SOURCE, source)
        }
        val pi = PendingIntent.getBroadcast(context, safeId(type, code) and 0x7fffffff, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val triggerAt = System.currentTimeMillis() + delayMs
        try {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (_: SecurityException) {
            // 无 SCHEDULE_EXACT_ALARM 权限时退化为普通 set（有延迟但可用）
            alarm.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /** RemindReceiver 在 onReceive 里调用：真正弹出提醒通知。 */
    fun showReminder(context: Context, code: String, type: CodeExtractor.CodeType, source: String) {
        if (code.isBlank()) return
        // Android 13+ 无通知权限时静默跳过（与 show/showDuplicate 一致），避免无效提醒
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            val style = typeStyle(type)
            val pendingIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, style.channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("⏰ 稍后提醒：${style.title} $code")
                .setContentText("记得去取：$code（$source）")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .build()
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            // 用独立 ID 空间，避免覆盖同码原始通知
            manager.notify((safeId(type, code) and 0x7fffffff) or 0x40000000, notification)
        } catch (_: Exception) { }
    }

    /** 取消已设置的稍后提醒闹钟（用户提前取件时调用）。 */
    fun cancelRemind(context: Context, code: String, type: CodeExtractor.CodeType) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, RemindReceiver::class.java)
        val pi = PendingIntent.getBroadcast(context, safeId(type, code) and 0x7fffffff, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pi)
        pi.cancel()
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
            context, safeId(type, code), intent,
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
        // 去重提示用独立 id（code+type 复合），避免与主通知 id 冲突
        nm.notify(("dup_${type.name}_$code").hashCode() and 0x7fffffff, notification)
    }
}
