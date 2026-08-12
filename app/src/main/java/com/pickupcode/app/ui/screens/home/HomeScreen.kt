package com.pickupcode.app.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pickupcode.app.data.AppDatabase
import com.pickupcode.app.data.CodeHistory
import com.pickupcode.app.ui.theme.TypeCoupon
import com.pickupcode.app.ui.theme.TypeFood
import com.pickupcode.app.ui.theme.TypeParcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    hasNotificationPermission: Boolean,
    isAccessibilityEnabled: Boolean,
    hideAccessibilityCard: Boolean,
    hideGuideCard: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onHideAccessibilityCard: () -> Unit,
    onHideGuideCard: () -> Unit,
    onSettingsClick: () -> Unit,
    onItemClick: (Long) -> Unit,
    onFabClick: () -> Unit,
    onTrashClick: () -> Unit,
    onStatsClick: () -> Unit,
    onDedupClick: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val activeHistory by remember { db.codeHistoryDao().getActiveFlow() }.collectAsState(initial = emptyList())
    val trashHistory by remember { db.codeHistoryDao().getTrashFlow() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var dedupCount by remember { mutableIntStateOf(0) }
    var typeFilter by remember { mutableStateOf("all") }
    var guideExpanded by remember { mutableStateOf(false) }

    val filteredHistory = remember(activeHistory, typeFilter) {
        activeHistory.filter { h ->
            when (typeFilter) {
                "food" -> h.type == "pickup_food"
                "parcel" -> h.type == "pickup_parcel"
                "coupon" -> h.type == "coupon"
                else -> true
            }
        }
    }

    // 时间分组（Medium-4: remember 键含日期，跨午夜后重组能重算 todayStart）
    val today = LocalDate.now()
    val todayStart = remember(today) {
        today.atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000
    }
    val yesterdayStart = todayStart - 24 * 60 * 60 * 1000

    val grouped: Map<String, List<CodeHistory>> = remember(filteredHistory) {
        filteredHistory.groupBy { item ->
            when {
                item.timestamp >= todayStart -> "今天"
                item.timestamp >= yesterdayStart -> "昨天"
                else -> "更早"
            }
        }
    }
            val groupOrder = listOf("今天", "昨天", "更早").filter { it in grouped }

            // 共享操作：标记已取/删除 → 移入回收站 → snackbar 撤销
            fun markAsDone(item: CodeHistory) {
                scope.launch(Dispatchers.IO) {
                    db.codeHistoryDao().markDoneByCodeAndType(item.code, item.type)
                    val result = withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(
                            message = "已移至回收站，24小时后自动删除",
                            actionLabel = "撤销",
                            duration = SnackbarDuration.Short
                        )
                    }
                    if (result == SnackbarResult.ActionPerformed) {
                        // H10: 撤销与归档范围一致——恢复同 code+type 的全部记录（markDoneByCodeAndType 归档的是同 code+type 全部）
                        trashHistory.filter { it.code == item.code && it.type == item.type }
                            .forEach { db.codeHistoryDao().restore(it.id) }
                    }
                }
            }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val oneDayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000
            val dao = db.codeHistoryDao()
            // 先清截图文件再删 DB 行
            dao.getExpiredScreenshots(oneDayAgo).forEach { path ->
                try { java.io.File(path).delete() } catch (_: Exception) {}
            }
            dao.deleteExpiredTrash(oneDayAgo)
        }
    }

    // Medium-3: 重复分组计数只随 activeHistory 变化查询，避免与上面的 LaunchedEffect(Unit) 重复查询
    LaunchedEffect(activeHistory) {
        withContext(Dispatchers.IO) {
            dedupCount = db.codeHistoryDao().countDuplicateGroups()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text("一键闪记", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                },
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
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onFabClick,
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, "手动输入")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // FilterChips
            item {
                FilterChipRow(currentFilter = typeFilter, onFilterChange = { typeFilter = it })
            }

            // 通知权限
            if (!hasNotificationPermission) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("🔔 需要通知权限", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text("开启后才能在锁屏/通知栏显示取餐取件码",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onRequestNotificationPermission) { Text("授权") }
                        }
                    }
                }
            }

            // 无障碍服务卡片
            if (!hideAccessibilityCard) {
                item {
                    val containerColor = if (isAccessibilityEnabled)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.tertiaryContainer
                    val title = if (isAccessibilityEnabled) "✅ 无障碍服务已开启" else "🔧 需要开启无障碍服务"

                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = containerColor),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(title, style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f))
                                IconButton(onClick = onHideAccessibilityCard, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "隐藏",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (isAccessibilityEnabled) {
                                Text("打开控制面板 → 点✏️编辑 → 找到「一键闪记」→ 拖到面板",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                Text("固定后点击磁贴，再在3秒内退出控制面板即可自动识别",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium)
                            } else {
                                Text("开启后点快捷设置磁贴即可自动识别屏幕上的取餐码/取件码",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = onEnableAccessibility) { Text("去开启") }
                            }
                        }
                    }
                }
            }

            // 引导卡片（可折叠）
            if (!hideGuideCard) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { guideExpanded = !guideExpanded }
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                RoundedCornerShape(14.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📥 怎么添加取餐码/取件码/券码?",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f))
                            Text(if (guideExpanded) "▴" else "▾",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AnimatedVisibility(visible = guideExpanded) {
                            Column {
                                Spacer(Modifier.height(6.dp))
                                Text("·从短信/聊天 App 分享", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("·点右下角 ➕ 手动粘贴", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("·通过无障碍服务调用OCR识别", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // 回收站提示
            if (trashHistory.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable(onClick = onTrashClick),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🗑️ 回收站有 ${trashHistory.size} 条记录，24小时后自动删除",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 去重提示
            if (dedupCount > 0) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable(onClick = onDedupClick),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🔄 发现 ${dedupCount} 组重复记录，点击查看 →",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            // 空状态
            if (filteredHistory.isEmpty()) {
                item {
                    if (typeFilter != "all") {
                        // 筛选后空状态：提示 + 清除筛选
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("没有符合筛选条件的记录",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(12.dp))
                                TextButton(onClick = {
                                    typeFilter = "all"
                                }) {
                                    Text("清除筛选条件")
                                }
                            }
                        }
                    } else {
                        // 全新空状态：三个引导入口卡片
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "欢迎使用一键闪记",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Text(
                                "选择一种方式开始记录取件码",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            // 方式一：无障碍自动识别
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { onEnableAccessibility() },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("🔧", fontSize = 28.sp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("开启无障碍自动识别",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold)
                                        Text("打开控制面板一键自动识别取件码",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            // 方式二：从截图/分享导入
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { onFabClick() },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("📸", fontSize = 28.sp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("从截图/分享导入",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold)
                                        Text("从其他App分享文本或截图识别",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            // 方式三：手动输入
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { onFabClick() },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("⌨️", fontSize = 28.sp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("手动输入取件码",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold)
                                        Text("直接输入或粘贴取件码文字",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            // 时间分组列表
            groupOrder.forEach { groupLabel ->
                item(key = "header_$groupLabel") {
                    TimeGroupHeader(label = groupLabel)
                }
                val groupItems = grouped[groupLabel] ?: emptyList()
                items(groupItems, key = { it.id }) { item ->
                    CodeHistoryCard(
                        item = item,
                        onClick = { onItemClick(item.id) },
                        onDone = { markAsDone(item) },
                        onDelete = { markAsDone(item) }
                    )
                }
            }
        }
    }
}
