package com.pickupcode.app.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import android.content.Intent
import android.util.Log

class PickupCodeTileService : TileService() {

    // 回主线程更新磁贴用（updateTile 必须在主线程）
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onClick() {
        super.onClick()
        if (isAccessibilityEnabled()) {
            PickupCodeAccessibilityService.triggerRequested.set(true)
            Log.d("PickupCodeTile", "触发标记已设置")
        } else {
            // 无障碍服务未连接：直接提示并跳转设置，避免点了没反应的困惑（M4）
            Log.d("PickupCodeTile", "无障碍服务未开启，跳转设置")
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.e("PickupCodeTile", "打开无障碍设置失败: ${e.message}")
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        // split + 精确比对包名+服务类名，避免 contains 模糊匹配误判（M4/MainActivity低危项同源）
        val target = "$packageName/${PickupCodeAccessibilityService::class.java.name}"
        return enabledServices.split(':').any { it.trim() == target }
    }

    override fun onStartListening() {
        super.onStartListening()
        // Low-2: 无障碍未启用时磁贴显示不可用（灰态），避免"看起来开着其实没反应"
        // Settings.Secure 读取涉及 Binder/IO，放子线程；磁贴更新回主线程
        Thread {
            val enabled = isAccessibilityEnabled()
            mainHandler.post {
                qsTile?.apply {
                    state = if (enabled) {
                        android.service.quicksettings.Tile.STATE_ACTIVE
                    } else {
                        android.service.quicksettings.Tile.STATE_UNAVAILABLE
                    }
                    updateTile()
                }
            }
        }.start()
    }
}
