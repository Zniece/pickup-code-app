package com.pickupcode.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pickupcode.app.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 通知栏「已取」按钮：标记 DB 记录为已完成并消除通知（后台线程，不阻塞主线程） */
class DoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val historyId = intent.getLongExtra("history_id", -1)
        val notificationId = intent.getIntExtra("notification_id", -1)

        val pending = goAsync()
        // 本地临时 scope：随本次 onReceive 生命周期走，既不泄漏也不污染实例/复用（避免 cancel 实例字段导致下次广播静默失败）
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val dao = AppDatabase.getInstance(context).codeHistoryDao()
                if (historyId > 0) {
                    // 与 App 内「标记已取」一致：归档该 code+type 的全部活跃记录（对齐 markDoneByCodeAndType）
                    val rec = dao.getByIdSuspend(historyId)
                    if (rec != null) {
                        dao.markDoneByCodeAndType(rec.code, rec.type)
                    } else {
                        dao.markDone(historyId)
                    }
                }
                if (notificationId != -1) {
                    CodeNotificationManager.dismissById(context, notificationId)
                }
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }
}
