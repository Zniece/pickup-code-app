package com.pickupcode.app

import android.app.Application
import com.pickupcode.app.notification.CodeNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        CodeNotificationManager.createChannels(this)
    }

    companion object {
        lateinit var instance: App
            private set

        // 全局协程作用域：供分享识别等「不依赖 Activity 生命周期」的后台任务使用，
        // 避免 Activity 销毁中断正在处理的识别流程（截图/OCR/DB写入）。
        val appScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    }
}
