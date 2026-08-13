package com.pickupcode.app.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 双音量键快捷触发（澎湃记方案，2026-08-13）：
 * 不监听 keyevent——利用 Android 系统自带「无障碍快捷方式」（系统设置里可配置
 * "同时按住两个音量键"触发本服务）。触发后立即 disableSelf 用完即走，不常驻。
 *
 * 配置方法：系统设置 → 无障碍 → 无障碍快捷方式 → 选择「码上闪记（音量键快捷识别）」。
 * 与常驻的 [PickupCodeAccessibilityService]（截图 OCR 核心）互不干扰：
 * 本服务只负责置 [PickupCodeAccessibilityService.triggerRequested] 标记，
 * 常驻服务的心跳（3 秒循环）检测到标记即执行截图识别——与磁贴完全同路径。
 */
class VolumeKeyShortcutService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 用完即走：本服务不常驻，仅作为系统快捷方式的触发通道
        disableSelf()
        // 复用磁贴的触发路径：置标记 → 常驻服务心跳检测到后执行截图识别
        PickupCodeAccessibilityService.triggerRequested.set(true)
        Log.d("VolumeKeyShortcut", "音量键触发：识别标记已设置")
    }
}
