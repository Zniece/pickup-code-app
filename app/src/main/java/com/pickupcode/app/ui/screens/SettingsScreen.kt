package com.pickupcode.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pickupcode.app.learner.PatternLearner
import com.pickupcode.app.BuildConfig
import com.pickupcode.app.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onStatsClick: () -> Unit = {}) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val s by AppPreferences.observe(ctx).collectAsState(initial = AppPreferences.Settings())
    var apiUrl by remember { mutableStateOf(s.apiBaseUrl) }
    var apiKey by remember { mutableStateOf(s.apiKey) }
    var apiModel by remember { mutableStateOf(s.apiModel) }
    var amapApiKey by remember { mutableStateOf(s.amapApiKey) }
    var kuaidi100Key by remember { mutableStateOf(s.kuaidi100Key) }

    // DataStore 异步加载真实值后回填一次，避免已配置的 Key 在重启后显示为空（M1）
    LaunchedEffect(Unit) {
        AppPreferences.observe(ctx).first().let {
            apiUrl = it.apiBaseUrl; apiKey = it.apiKey; apiModel = it.apiModel
            amapApiKey = it.amapApiKey; kuaidi100Key = it.kuaidi100Key
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {

            item { Text("识别灵敏度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { Text("阈值越低越宽松，越高越严格", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                var confDraft by remember { mutableStateOf(s.confidenceThreshold) }
                Slider(value = confDraft,
                    onValueChange = { confDraft = it },
                    onValueChangeFinished = { scope.launch(Dispatchers.IO) { AppPreferences.setConfidenceThreshold(ctx, confDraft) } },
                    valueRange = 0.1f..0.8f, modifier = Modifier.weight(1f))
                Text("${(confDraft * 100).roundToInt()}%", modifier = Modifier.padding(start = 8.dp)) } }
            item { HorizontalDivider() }

            item { Text("识别类型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { Switch("🥤 取餐码", checked = s.enableFoodCodes) { scope.launch(Dispatchers.IO) { AppPreferences.setEnableFood(ctx, it) } } }
            item { Switch("📦 取件码", checked = s.enableParcelCodes) { scope.launch(Dispatchers.IO) { AppPreferences.setEnableParcel(ctx, it) } } }
            item { Switch("🎫 券码", sub = "识别屏幕/图片中的二维码（解码内容为码值；识别到则只标券码，不叠加取餐/取件码）", checked = s.enableCouponCodes) { scope.launch(Dispatchers.IO) { AppPreferences.setEnableCoupon(ctx, it) } } }
            item { HorizontalDivider() }

            item { Text("📥 外部接收", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { Text("从其他App分享或拖放到本应用时自动识别", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Switch("🔗 Intent 接收", sub = "接收来自其他App的分享（文本/图片）", checked = s.enableIntentReceive) { scope.launch(Dispatchers.IO) { AppPreferences.setEnableIntentReceive(ctx, it) } } }
            item { Switch("📤 分享识别", sub = "文本选择菜单/拖放直达时自动识别取餐取件码", checked = s.enableShareDetection) { scope.launch(Dispatchers.IO) { AppPreferences.setEnableShareDetection(ctx, it) } } }
            item { HorizontalDivider() }

            item { Text("🔧 无障碍服务提示", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { Text("主页的无障碍服务引导卡片，可以随时隐藏", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { OutlinedButton(
                onClick = { scope.launch(Dispatchers.IO) { AppPreferences.setHideAccessibilityCard(ctx, false) } },
                enabled = s.hideAccessibilityCard,
                modifier = Modifier.fillMaxWidth()) { Text("在主页重新显示无障碍提示") } }
            item { HorizontalDivider() }

            item { Text("🗺️ 地图验证", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { Text("提取地址后自动查询地图验证真实性，辅助自学习评级", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Switch("启用地图验证", sub = if (s.enableMapVerify) "已启用" else "已关闭（隐私优先）", checked = s.enableMapVerify) { scope.launch(Dispatchers.IO) { AppPreferences.setEnableMapVerify(ctx, it) } } }
            if (s.enableMapVerify) {
                item { Text("Android 地理编码器优先使用，无需配置。如需更高精度可填高德 API Key：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { DebouncedKeyField(value = amapApiKey, label = "高德 API Key（可选）", onCommit = { scope.launch(Dispatchers.IO) { AppPreferences.setAmapApiKey(ctx, it) } }, onChange = { amapApiKey = it }) }
                item { AmapHelpSection() }
            }
            item { HorizontalDivider() }

            item { Text("📮 快递100验证", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { Text("通过快递单号查询取件码和地址，验证OCR结果", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Switch("启用快递100验证", sub = if (s.enableKuaidi100) "已启用" else "已关闭", checked = s.enableKuaidi100) { scope.launch(Dispatchers.IO) { AppPreferences.setEnableKuaidi100(ctx, it) } } }
            if (s.enableKuaidi100) {
                item { DebouncedKeyField(value = kuaidi100Key, label = "快递100 API Key", onCommit = { scope.launch(Dispatchers.IO) { AppPreferences.setKuaidi100Key(ctx, it) } }, onChange = { kuaidi100Key = it }) }
                item { Kuaidi100HelpSection() }
            }
            item { HorizontalDivider() }

            item { Text("📊 自学习统计", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { OutlinedButton(onClick = onStatsClick, modifier = Modifier.fillMaxWidth()) { Text("查看详细统计 →") } }
            item { LearningStatsPanel(ctx, scope) }
            item { HorizontalDivider() }

            item { Text("🤖 AI 识别", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { Switch("启用 AI 识别", sub = if (s.enableAI) { if (s.apiKey.isNotBlank()) "已开启" else "未配置 API Key" } else "已关闭",
                checked = s.enableAI) { scope.launch(Dispatchers.IO) { AppPreferences.setEnableAI(ctx, it) } } }

            if (s.enableAI) {
                item { DebouncedKeyField(value = apiUrl, label = "API 地址", onCommit = { scope.launch(Dispatchers.IO) { AppPreferences.setApiBaseUrl(ctx, it) } }, onChange = { apiUrl = it }) }
                item { DebouncedKeyField(value = apiKey, label = "API Key", isPassword = true, onCommit = { scope.launch(Dispatchers.IO) { AppPreferences.setApiKey(ctx, it) } }, onChange = { apiKey = it }) }
                item { DebouncedKeyField(value = apiModel, label = "模型名称", onCommit = { scope.launch(Dispatchers.IO) { AppPreferences.setApiModel(ctx, it) } }, onChange = { apiModel = it }) }
            }
            item { HorizontalDivider() }

            item { Text("关于", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { Column { Text("一键闪记 v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                Text("基于 ML Kit OCR · 数据仅存储在本地", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                val uriHandler = LocalUriHandler.current
                Text("GitHub: https://github.com/zixij644-elaborate/pickup-code-app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { uriHandler.openUri("https://github.com/zixij644-elaborate/pickup-code-app") }
                        .padding(vertical = 2.dp)) } }
            item {
                var upStatus by remember { mutableStateOf<String?>(null) }; var checking by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { checking = true; scope.launch { upStatus = checkUpdate(); checking = false } }, enabled = !checking) {
                    Text(if (checking) "检查中..." else "检查更新") }
                upStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun LearningStatsPanel(ctx: android.content.Context, scope: kotlinx.coroutines.CoroutineScope) {
    var stats by remember { mutableStateOf<PatternLearner.PatternStats?>(null) }
    var addrStats by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var suggestions by remember { mutableStateOf<List<PatternLearner.PatternSuggestion>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            stats = PatternLearner.getStats(ctx)
            addrStats = PatternLearner.getAddressStats(ctx)
            suggestions = PatternLearner.getSuggestions(ctx)
        }
    }

    val s = stats
    if (s == null || s.totalScans == 0) {
        Text("暂无识别数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    val hitRate = if (s.attempts > 0) (s.attempts.toFloat() / s.totalScans * 100).roundToInt() else 0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("扫描 ${s.totalScans} 次 · 命中 ${s.attempts} 次（${hitRate}%）· 漏检 ${s.misses} 次", style = MaterialTheme.typography.bodyMedium)
        if (s.verified > 0) {
            Text("已确认 ${s.verified} 次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        if (s.perPattern.isNotEmpty()) {
            Text("格式命中：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            for ((p, n) in s.perPattern.entries.sortedByDescending { it.value }) {
                Text("  $p : $n 次", style = MaterialTheme.typography.bodySmall)
            }
        }

        val (addrOk, addrTotal) = addrStats ?: (0 to 0)
        if (addrTotal > 0) {
            val addrRate = (addrOk.toFloat() / addrTotal * 100).roundToInt()
            Text("地址验证：$addrOk / $addrTotal（${addrRate}%）", style = MaterialTheme.typography.bodySmall)
        }

        if (suggestions.isNotEmpty()) {
            Text("候选模式：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            for (sg in suggestions.take(3)) {
                Text("  ${sg.label} — ${sg.count} 条未匹配", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Text("  样例: ${sg.sampleCodes.joinToString("，")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("  建议: ${sg.proposedRegex}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }

        TextButton(onClick = {
            scope.launch(Dispatchers.IO) {
                PatternLearner.clearUnmatched(ctx)
                suggestions = PatternLearner.getSuggestions(ctx)
            }
        }) { Text("清除未匹配样本", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun DebouncedKeyField(
    value: String,
    label: String,
    onCommit: (String) -> Unit,
    onChange: (String) -> Unit,
    isPassword: Boolean = false,
    debounceMs: Long = 400
) {
    var text by remember { mutableStateOf(value) }
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 用 remember 持有 Job，避免重组时重置为 null 导致防抖失效（H5）
    val saveJob = remember { mutableStateOf<Job?>(null) }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
            saveJob.value?.cancel()
            saveJob.value = scope.launch {
                delay(debounceMs)
                onCommit(text)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        visualTransformation = if (isPassword && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (isPassword) {
            {
                TextButton(onClick = { visible = !visible }) {
                    Text(if (visible) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall)
                }
            }
        } else null
    )
}

@Composable
private fun AmapHelpSection() {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起说明 ▲" else "如何获取高德 API Key？▼", style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("1. 打开浏览器访问", style = MaterialTheme.typography.bodySmall)
                    Text("   https://console.amap.com/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text("2. 注册/登录高德开放平台账号", style = MaterialTheme.typography.bodySmall)
                    Text("3. 进入「应用管理 → 我的应用」→ 创建应用", style = MaterialTheme.typography.bodySmall)
                    Text("4. 为应用添加 Key，服务平台选择「Web服务」", style = MaterialTheme.typography.bodySmall)
                    Text("5. 复制生成的 Key 粘贴到上方输入框", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("免费额度：每日 3000 次地理编码，个人使用完全够用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Kuaidi100HelpSection() {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起说明 ▲" else "如何获取快递100 API Key？▼", style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("1. 打开浏览器访问", style = MaterialTheme.typography.bodySmall)
                    Text("   https://api.kuaidi100.com/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text("2. 注册/登录快递100开放平台", style = MaterialTheme.typography.bodySmall)
                    Text("3. 进入「企业管理 → 我的授权Key」", style = MaterialTheme.typography.bodySmall)
                    Text("4. 复制 Customer Key 粘贴到上方输入框", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("用途：OCR 提取到单号后，通过API反向查取件码和地址作为标准答案", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("用于验证 OCR 提取结果是否正确，辅助自学习", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Switch(title: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge); sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Switch(checked = checked, onCheckedChange = onChange) }
}

private suspend fun checkUpdate(): String = withContext(Dispatchers.IO) {
    var resp: java.net.HttpURLConnection? = null
    try {
        resp = java.net.URL("https://api.github.com/repos/zixij644-elaborate/pickup-code-app/releases/latest")
            .openConnection() as java.net.HttpURLConnection
        resp.requestMethod = "GET"; resp.setRequestProperty("Accept", "application/vnd.github.v3+json")
        resp.setRequestProperty("User-Agent", "pickup-code-app-checkupdate")
        resp.connectTimeout = 10000; resp.readTimeout = 10000
        if (resp.responseCode != 200) return@withContext "检查失败"
        val latest = org.json.JSONObject(resp.inputStream.bufferedReader().use { it.readText() }).getString("tag_name").removePrefix("v")
        if (latest == BuildConfig.VERSION_NAME) "当前已是最新版本" else "发现新版本 v$latest"
    } catch (e: Exception) { "检查失败: ${e.message}" }
    finally { resp?.disconnect() }
}
