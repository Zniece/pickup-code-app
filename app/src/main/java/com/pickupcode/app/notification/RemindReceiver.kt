package com.pickupcode.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pickupcode.app.extractor.CodeExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** C3: 稍后提醒/到期提醒 —— 收到 AlarmManager 定时广播后，复查状态并推提醒通知。 */
class RemindReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra("remind_code") ?: return
        val typeName = intent.getStringExtra("remind_type") ?: return
        val source = intent.getStringExtra("remind_source").orEmpty()
        val kind = intent.getStringExtra("remind_kind") ?: "later"
        val type = runCatching { CodeExtractor.CodeType.valueOf(typeName) }
            .getOrDefault(CodeExtractor.CodeType.pickup_parcel)
        // 发前复查：码已标记已取（无活跃记录）则静默放弃，防假警报
        // goAsync：onReceive 返回后保持进程存活直到复查完成（异步 DB 查询必需）
        val pendingResult = goAsync()
        scope.launch {
            try {
                val dao = com.pickupcode.app.data.AppDatabase.getInstance(context).codeHistoryDao()
                val activeCount = runCatching { dao.countActiveByCodeAndType(code, type.name) }.getOrDefault(0)
                if (activeCount > 0) {
                    CodeNotificationManager.showReminder(context, code, type, source, kind)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
