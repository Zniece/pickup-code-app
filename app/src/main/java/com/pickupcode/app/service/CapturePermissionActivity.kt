package com.pickupcode.app.service

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.atomic.AtomicReference

/**
 * 透明的 Activity，仅用于请求 MediaProjection 截图权限。
 * 权限结果通过静态 AtomicReference 安全传递给磁贴服务。
 */
class CapturePermissionActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CapturePermActivity"

        /** 线程安全的回调引用。磁贴设置回调，此 Activity 读取并调用。 */
        private val callbackRef = AtomicReference<((Int, Intent) -> Unit)?>(null)

        /** 注册回调，由磁贴服务在启动此 Activity 前调用 */
        fun setCallback(cb: (Int, Intent) -> Unit) {
            callbackRef.set(cb)
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.d(TAG, "授权成功")
            callbackRef.getAndSet(null)?.invoke(result.resultCode, result.data!!)
        } else {
            Log.w(TAG, "授权失败或被用户拒绝")
            callbackRef.set(null)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "请求 MediaProjection 权限")
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mgr.createScreenCaptureIntent()
        projectionLauncher.launch(intent)
    }
}
