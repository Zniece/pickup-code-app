package com.pickupcode.app

import android.os.Build
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.pickupcode.app.data.AppDatabase
import com.pickupcode.app.data.CodeHistory
import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.preferences.AppPreferences
import com.pickupcode.app.share.ShareReceiver
import com.pickupcode.app.service.PickupCodeAccessibilityService
import com.pickupcode.app.ui.components.ManualCodeDialog
import com.pickupcode.app.ui.components.NotificationPermissionBanner
import com.pickupcode.app.ui.screens.CodeDetailScreen
import com.pickupcode.app.ui.screens.DedupScreen
import com.pickupcode.app.ui.screens.SettingsScreen
import com.pickupcode.app.ui.screens.StatsScreen
import com.pickupcode.app.ui.theme.PickupCodeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    private var hasNotificationPermission by mutableStateOf(false)
    private var isAccessibilityEnabled by mutableStateOf(false)
    private var currentScreen by mutableStateOf(Screen.Home)
    private var selectedCodeId by mutableStateOf(-1L)
    private var showManualDialog by mutableStateOf(false)

    enum class Screen { Home, Settings, Detail, Trash, Stats, Dedup }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasNotificationPermission = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 处理外部分享/拖放 Intent（首次启动时）
        ShareReceiver.handle(this, intent, App.appScope)

        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

        setContent {
            val settings by AppPreferences.observe(this)
                .collectAsState(initial = AppPreferences.Settings())

            BackHandler(enabled = currentScreen != Screen.Home) {
                currentScreen = Screen.Home
            }

            isAccessibilityEnabled = isAccessibilityServiceEnabled()

            PickupCodeTheme {
                when (currentScreen) {
                    Screen.Home -> MainScreen(
                        hasNotificationPermission = hasNotificationPermission,
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        hideAccessibilityCard = settings.hideAccessibilityCard,
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
                        onSettingsClick = { currentScreen = Screen.Settings },
                        onItemClick = { id ->
                            selectedCodeId = id
                            currentScreen = Screen.Detail
                        },
                        onFabClick = { showManualDialog = true },
                        onTrashClick = { currentScreen = Screen.Trash },
                        onStatsClick = { currentScreen = Screen.Stats },
                        onDedupClick = { currentScreen = Screen.Dedup },
                        onOpenShareGuide = { currentScreen = Screen.Settings }
                    )
                    Screen.Settings -> SettingsScreen(
                        onBack = { currentScreen = Screen.Home },
                        onStatsClick = { currentScreen = Screen.Stats }
                    )
                    Screen.Detail -> DetailScreenWrapper(
                        codeId = selectedCodeId,
                        onBack = { currentScreen = Screen.Home }
                    )
                    Screen.Trash -> TrashScreen(
                        onBack = { currentScreen = Screen.Home }
                    )
                    Screen.Stats -> StatsScreen(
                        onBack = { currentScreen = Screen.Home }
                    )
                    Screen.Dedup -> DedupScreen(
                        onBack = { currentScreen = Screen.Home }
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
                        // Batch delete all duplicates
                        item?.let { db.codeHistoryDao().markDoneByCodeAndType(it.code, it.type) }
                    }
                    onBack()
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
            db.codeHistoryDao().insert(
                CodeHistory(
                    code = code,
                    type = type,
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
            Toast.makeText(this, "在列表中找到「一键闪记」并开启", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    hasNotificationPermission: Boolean,
    isAccessibilityEnabled: Boolean,
    hideAccessibilityCard: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onHideAccessibilityCard: () -> Unit,
    onSettingsClick: () -> Unit,
    onItemClick: (Long) -> Unit,
    onFabClick: () -> Unit,
    onTrashClick: () -> Unit,
    onStatsClick: () -> Unit,
    onDedupClick: () -> Unit,
    onOpenShareGuide: () -> Unit
) {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val activeHistory by db.codeHistoryDao().getActiveFlow().collectAsState(initial = emptyList())
    val trashHistory by db.codeHistoryDao().getTrashFlow().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var dedupCount by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf("all") } // all / food / parcel / coupon

    // 按搜索词 + 类型筛选
    val filteredHistory = remember(activeHistory, searchQuery, typeFilter) {
        activeHistory.filter { h ->
            val typeOk = when (typeFilter) {
                "food" -> h.type == "pickup_food"
                "parcel" -> h.type == "pickup_parcel"
                "coupon" -> h.type == "coupon"
                else -> true
            }
            val q = searchQuery.trim()
            val queryOk = if (q.isEmpty()) true else
                h.code.contains(q, ignoreCase = true) ||
                h.source.contains(q, ignoreCase = true) ||
                h.pickupAddress.contains(q, ignoreCase = true)
            typeOk && queryOk
        }
    }

    // 自动清理过期回收站记录
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val oneDayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000
            db.codeHistoryDao().deleteExpiredTrash(oneDayAgo)
            dedupCount = db.codeHistoryDao().countDuplicateGroups()
        }
    }

    // 监听活跃记录变化时刷新去重计数
    LaunchedEffect(activeHistory) {
        scope.launch(Dispatchers.IO) {
            dedupCount = db.codeHistoryDao().countDuplicateGroups()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("一键闪记") },
                actions = {
                    IconButton(onClick = onStatsClick) {
                        Icon(Icons.Default.Info, "统计")
                    }
                    IconButton(onClick = onTrashClick) {
                        Icon(Icons.Default.RestoreFromTrash, "回收站")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.AutoMirrored.Filled.List, "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onFabClick) {
                Icon(Icons.Default.Add, "手动输入")
            }
        }
    ) { padding ->
        // Sleek 纯白背景（极简；去掉渐变以匹配 Sleek 干净利落的视觉）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(12.dp, 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 通知权限提示（与下方无障碍卡统一内边距，保证大小一致）
            item { NotificationPermissionBanner(hasPermission = hasNotificationPermission, onRequestPermission = onRequestNotificationPermission) }

            // 无障碍服务引导 / 使用提示（可隐藏）
            if (!hideAccessibilityCard) {
                if (!isAccessibilityEnabled) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🔧 需要开启无障碍服务", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    IconButton(onClick = onHideAccessibilityCard, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "隐藏", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("开启后点快捷设置磁贴即可自动识别屏幕上的取餐码/取件码", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = onEnableAccessibility) { Text("去开启") }
                            }
                        }
                    }
                } else {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("✅ 无障碍服务已开启", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    IconButton(onClick = onHideAccessibilityCard, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "隐藏", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text("打开控制面板 → 点✏️编辑 → 找到「一键闪记」→ 拖到面板", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                Text("固定后点击磁贴，再在3秒内退出控制面板即可自动识别", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            // C1: 多入口使用引导卡（弱化无障碍依赖，突出分享/通知/手动）
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("📥 怎么添加取件码？", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("① 从短信/聊天 App 分享文本进来", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("② 长按选中文字 → 选择「一键闪记」", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("③ 点右下角 ➕ 手动粘贴", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = onOpenShareGuide,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF8DC0E0))
                        ) { Text("开启分享识别 →") }
                    }
                }
            }

            // 回收站提示
            if (trashHistory.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onTrashClick),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🗑️ 回收站有 ${trashHistory.size} 条记录，24小时后自动删除", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 去重提示
            if (dedupCount > 0) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onDedupClick),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🔄 发现 ${dedupCount} 组重复记录，点击查看 →", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            // 类型筛选
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 类型筛选 chip
                    listOf("all" to "全部", "food" to "🥤取餐", "parcel" to "📦取件", "coupon" to "🎟️券码").forEach { (k, label) ->
                        FilterChip(
                            selected = typeFilter == k,
                            onClick = { typeFilter = k },
                            label = { Text(label) }
                        )
                    }
                }
            }

            // 历史记录标题
            item { Text("历史记录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 2.dp)) }

            // 历史列表 / 空状态
            if (filteredHistory.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (activeHistory.isEmpty()) "还没有记录\n打开外卖/快递App自动识别\n或点右下角手动输入"
                            else "没有符合筛选条件的记录",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            } else {
                items(filteredHistory, key = { it.id }) { item ->
                    CodeHistoryCard(
                        item = item,
                        onClick = { onItemClick(item.id) },
                        onDelete = {
                            scope.launch(Dispatchers.IO) {
                                db.codeHistoryDao().markDoneByCodeAndType(item.code, item.type)
                                val snackbarResult = snackbarHostState.showSnackbar(message = "已移至回收站，24小时后自动删除", actionLabel = "撤销", duration = SnackbarDuration.Short)
                                if (snackbarResult == SnackbarResult.ActionPerformed) { db.codeHistoryDao().restore(item.id) }
                            }
                        },
                        onDone = {
                            scope.launch(Dispatchers.IO) {
                                db.codeHistoryDao().markDoneByCodeAndType(item.code, item.type)
                                val snackbarResult = snackbarHostState.showSnackbar(message = "已移至回收站，24小时后自动删除", actionLabel = "撤销", duration = SnackbarDuration.Short)
                                if (snackbarResult == SnackbarResult.ActionPerformed) { db.codeHistoryDao().restore(item.id) }
                            }
                        },
                        onOpenSource = { pkg -> com.pickupcode.app.share.ShareReceiver.openApp(context, pkg) }
                    )
                }
            }
        }
        }
    }
}

@Composable
fun CodeHistoryCard(
    item: CodeHistory,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onDone: (() -> Unit)? = null,
    onOpenSource: ((String) -> Unit)? = null
) {
    // 卡片：浅灰容器，Spacious 下突出卡片层次
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.code,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "${item.source} · ${formatTime(item.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.pickupAddress.isNotBlank()) {
                    Text(
                        "📍 ${item.pickupAddress}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                Text(
                    "点击查看详情 ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                when (item.type) {
                    "pickup_food" -> "🥤"
                    "coupon" -> "🎟️"
                    else -> "📦"
                },
                style = MaterialTheme.typography.headlineMedium
            )
            // 右侧操作区（B 布局）：✅ 主位（左）+ 右边上下 🚪(上)/🗑️(下)，三者等大
            Row(verticalAlignment = Alignment.CenterVertically) {
                // ✅ 主位：标记已取（与类型 emoji 同大的 headlineMedium 尺寸）
                if (onDone != null) {
                    IconButton(onClick = onDone, modifier = Modifier.size(52.dp)) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "标记已取",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(MaterialTheme.typography.headlineMedium.fontSize.value.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.size(52.dp))
                }
                // 右列：上 🚪 / 下 🗑️
                Column(horizontalAlignment = Alignment.End) {
                    // 🚪 右上：跳转来源 App（与类型 emoji 同大），仅当有分享来源时显示
                    if (onOpenSource != null && item.shareSourcePkg.isNotBlank()) {
                        IconButton(onClick = { onOpenSource(item.shareSourcePkg) }, modifier = Modifier.size(52.dp)) {
                            Text(
                                "🚪",
                                fontSize = MaterialTheme.typography.headlineMedium.fontSize
                            )
                        }
                    } else {
                        Spacer(Modifier.size(52.dp))
                    }
                    // 🗑️ 右下：删除（与类型 emoji 同大）
                    IconButton(onClick = onDelete, modifier = Modifier.size(52.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MaterialTheme.typography.headlineMedium.fontSize.value.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val trashHistory by db.codeHistoryDao().getTrashFlow().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                "已取/已删除的记录在此保留24小时，之后自动彻底清除",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            if (trashHistory.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "回收站是空的",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(trashHistory, key = { it.id }) { item ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.code,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "${item.source} · ${formatTime(item.timestamp)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "剩余 ${formatRemaining(item.doneAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                TextButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        db.codeHistoryDao().restore(item.id)
                                    }
                                }) {
                                    Text("恢复")
                                }
                                TextButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        db.codeHistoryDao().deleteById(item.id)
                                    }
                                }) {
                                    Text("删除", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    return Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}

private fun formatRemaining(doneAt: Long): String {
    val remaining = doneAt + 24 * 60 * 60 * 1000 - System.currentTimeMillis()
    if (remaining <= 0) return "即将删除"
    val hours = remaining / (60 * 60 * 1000)
    val minutes = (remaining % (60 * 60 * 1000)) / (60 * 1000)
    return if (hours > 0) "${hours}小时${minutes}分" else "${minutes}分钟"
}
