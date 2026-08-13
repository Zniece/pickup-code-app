package com.pickupcode.app

import android.os.Build
import android.content.Intent
import android.util.Log
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.pickupcode.app.data.AppDatabase
import com.pickupcode.app.data.CodeHistory
import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.preferences.AppPreferences
import com.pickupcode.app.share.ShareReceiver
import com.pickupcode.app.service.PickupCodeAccessibilityService
import com.pickupcode.app.ui.components.ManualCodeDialog
import com.pickupcode.app.ui.screens.CodeDetailScreen
import com.pickupcode.app.ui.screens.DedupScreen
import com.pickupcode.app.ui.screens.SettingsScreen
import com.pickupcode.app.ui.screens.StatsScreen
import com.pickupcode.app.ui.screens.home.HomeScreen
import com.pickupcode.app.ui.screens.trash.TrashScreen
import com.pickupcode.app.ui.theme.PickupCodeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var hasNotificationPermission by mutableStateOf(false)
    private var isAccessibilityEnabled by mutableStateOf(false)
    // Medium-1: currentScreen/selectedCodeId/showManualDialog 已移至 setContent 内用 rememberSaveable 管理（旋转不丢状态）
    // B3: showDuplicate 通知点击后待处理的去重入口跳转（onCreate/onNewIntent 置位，组合期消费）
    private var pendingDedup by mutableStateOf(false)

    enum class Screen { Home, Settings, Detail, Trash, Stats, Dedup }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasNotificationPermission = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 处理外部分享/拖放 Intent（首次启动时）
        ShareReceiver.handle(this, intent, App.appScope)
        // B3: 消费通知导航 extra（showDuplicate 的 show_dedup）
        consumeNotificationExtras(intent)

        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

        // Medium-2: 组合期不再同步读无障碍状态（每次重组重复 binder 调用）；onCreate 先赋值一次，onResume 持续刷新
        isAccessibilityEnabled = isAccessibilityServiceEnabled()

        setContent {
            // Medium-1: 导航状态用 rememberSaveable 管理，旋转屏幕不丢失
            var currentScreen by rememberSaveable { mutableStateOf(Screen.Home.name) }
            var selectedCodeId by rememberSaveable { mutableLongStateOf(-1L) }
            var showManualDialog by rememberSaveable { mutableStateOf(false) }
            val screen = Screen.valueOf(currentScreen)

            val settings by AppPreferences.observe(this)
                .collectAsState(initial = AppPreferences.Settings())

            BackHandler(enabled = screen != Screen.Home) {
                currentScreen = Screen.Home.name
            }

            // B3: 消费通知 extra 驱动的去重页跳转（组合期用 LaunchedEffect 置状态，避免直接改路由）
            LaunchedEffect(pendingDedup) {
                if (pendingDedup) {
                    currentScreen = Screen.Dedup.name
                    pendingDedup = false
                }
            }

            PickupCodeTheme {
                when (screen) {
                    Screen.Home -> HomeScreen(
                        hasNotificationPermission = hasNotificationPermission,
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        hideAccessibilityCard = settings.hideAccessibilityCard,
                        hideGuideCard = settings.hideGuideCard,
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(
                                    android.Manifest.permission.POST_NOTIFICATIONS
                                )
                            }
                        },
                        onEnableAccessibility = { openAccessibilitySettings() },
                        onHideAccessibilityCard = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                AppPreferences.setHideAccessibilityCard(this@MainActivity, true)
                            }
                        },
                        onHideGuideCard = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                AppPreferences.setHideGuideCard(this@MainActivity, true)
                            }
                        },
                        onSettingsClick = { currentScreen = Screen.Settings.name },
                        onItemClick = { id ->
                            selectedCodeId = id
                            currentScreen = Screen.Detail.name
                        },
                        onFabClick = { showManualDialog = true },
                        onTrashClick = { currentScreen = Screen.Trash.name },
                        onStatsClick = { currentScreen = Screen.Stats.name },
                        onDedupClick = { currentScreen = Screen.Dedup.name }
                    )
                    Screen.Settings -> SettingsScreen(
                        onBack = { currentScreen = Screen.Home.name },
                        onStatsClick = { currentScreen = Screen.Stats.name }
                    )
                    Screen.Detail -> DetailScreenWrapper(
                        codeId = selectedCodeId,
                        onBack = { currentScreen = Screen.Home.name }
                    )
                    Screen.Trash -> TrashScreen(
                        onBack = { currentScreen = Screen.Home.name }
                    )
                    Screen.Stats -> StatsScreen(
                        onBack = { currentScreen = Screen.Home.name }
                    )
                    Screen.Dedup -> DedupScreen(
                        onBack = { currentScreen = Screen.Home.name }
                    )
                }

                if (showManualDialog) {
                    ManualCodeDialog(
                        onDismiss = { showManualDialog = false },
                        onConfirm = { code, type, source ->
                            saveManualCode(code, type, source)
                            showManualDialog = false
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 处理外部分享/拖放 Intent（App已在运行中时）
        ShareReceiver.handle(this, intent, App.appScope)
        // B3: 消费通知导航 extra（showDuplicate 的 show_dedup）
        consumeNotificationExtras(intent)
    }

    /** B3: 读取并消费通知导航 extra（show_dedup → 跳转去重整理页）。 */
    private fun consumeNotificationExtras(intent: Intent) {
        if (intent.getBooleanExtra("show_dedup", false)) {
            intent.removeExtra("show_dedup")
            pendingDedup = true
        }
    }

    override fun onResume() {
        super.onResume()
        isAccessibilityEnabled = isAccessibilityServiceEnabled()
    }

    @Composable
    private fun DetailScreenWrapper(codeId: Long, onBack: () -> Unit) {
        val db = AppDatabase.getInstance(this)
        val item by db.codeHistoryDao().getById(codeId).collectAsState(initial = null)

        item?.let { code ->
            CodeDetailScreen(
                item = code,
                onBack = onBack,
                onUpdated = { updated ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.codeHistoryDao().update(updated)
                    }
                },
                onMarkDone = { id ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            item?.let { db.codeHistoryDao().markDoneByCodeAndType(it.code, it.type) }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "标记已取失败", e)
                        }
                    }.invokeOnCompletion { onBack() }
                }
            )
        } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("加载中...")
        }
    }

    private fun saveManualCode(code: String, type: String, source: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(this@MainActivity)
            val codeType = when (type) {
                "pickup_food" -> CodeExtractor.CodeType.pickup_food
                "pickup_parcel" -> CodeExtractor.CodeType.pickup_parcel
                else -> CodeExtractor.CodeType.pickup_food // 手动录入只支持取餐/取件；默认取餐
            }
            db.codeHistoryDao().insertCheckDuplicate(
                CodeHistory(
                    code = code,
                    type = codeType.name,
                    source = source,
                    rawTextSnippet = "手动输入"
                )
            )
            com.pickupcode.app.notification.CodeNotificationManager
                .show(this@MainActivity, code, codeType, source)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        // split + 精确比对包名/服务类名，避免 contains 模糊匹配误判
        val target = "$packageName/${PickupCodeAccessibilityService::class.java.name}"
        return enabledServices.split(':').any { it.trim() == target }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "在列表中找到「码上闪记」并开启", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
        }
    }
}

// Removed old MainScreen/CodeHistoryCard/TrashScreen composables
// Now in ui/screens/home/ and ui/screens/trash/
