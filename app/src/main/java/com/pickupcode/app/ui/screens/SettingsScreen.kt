package com.pickupcode.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pickupcode.app.BuildConfig
import com.pickupcode.app.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by AppPreferences.observe(context)
        .collectAsState(initial = AppPreferences.Settings())

    var apiBaseUrl by remember { mutableStateOf(settings.apiBaseUrl) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var apiModel by remember { mutableStateOf(settings.apiModel) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
        ) {
            // ── 识别灵敏度 ──
            item {
                Text("识别灵敏度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            item {
                Text("阈值越低越宽松，越高越严格",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = settings.confidenceThreshold,
                        onValueChange = { v ->
                            scope.launch(Dispatchers.IO) { AppPreferences.setConfidenceThreshold(context, v) }
                        },
                        valueRange = 0.1f..0.8f,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${(settings.confidenceThreshold * 100).roundToInt()}%",
                        modifier = Modifier.padding(start = 8.dp))
                }
            }

            item { HorizontalDivider() }

            // ── 识别类型 ──
            item {
                Text("识别类型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            item {
                SwitchRow("🥤 取餐码", "纯数字取餐号（如 229、A-356）",
                    checked = settings.enableFoodCodes,
                    onChanged = { scope.launch(Dispatchers.IO) { AppPreferences.setEnableFood(context, it) } })
            }
            item {
                SwitchRow("📦 取件码", "快递驿站编码（如 10-2-7507）",
                    checked = settings.enableParcelCodes,
                    onChanged = { scope.launch(Dispatchers.IO) { AppPreferences.setEnableParcel(context, it) } })
            }

            item { HorizontalDivider() }

            // ── AI 识别 ──
            item {
                Text("🤖 AI 识别", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            item {
                Text("开启后优先用 AI 提取取餐码，关闭则仅使用正则匹配",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                SwitchRow(
                    title = "启用 AI 识别",
                    subtitle = if (settings.enableAI) {
                        if (settings.apiKey.isNotBlank()) "已开启，优先使用 AI" else "已开启，但未配置 API Key，使用正则"
                    } else {
                        "已关闭，仅使用正则匹配"
                    },
                    checked = settings.enableAI,
                    onChanged = { scope.launch(Dispatchers.IO) { AppPreferences.setEnableAI(context, it) } }
                )
            }

            if (settings.enableAI) {
                item {
                    OutlinedTextField(apiBaseUrl, { apiBaseUrl = it; scope.launch(Dispatchers.IO) { AppPreferences.setApiBaseUrl(context, it) } },
                        Modifier.fillMaxWidth(), singleLine = true, label = { Text("API 地址") })
                }
                item {
                    OutlinedTextField(apiKey, { apiKey = it; scope.launch(Dispatchers.IO) { AppPreferences.setApiKey(context, it) } },
                        Modifier.fillMaxWidth(), singleLine = true, label = { Text("API Key") })
                }
                item {
                    OutlinedTextField(apiModel, { apiModel = it; scope.launch(Dispatchers.IO) { AppPreferences.setApiModel(context, it) } },
                        Modifier.fillMaxWidth(), singleLine = true, label = { Text("模型名称") })
                }
            }

            item { HorizontalDivider() }

            // ── 外观 ──
            item {
                Text("外观", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEachIndexed { i, (v, l) ->
                        SegmentedButton(
                            selected = settings.darkMode == v,
                            onClick = { scope.launch(Dispatchers.IO) { AppPreferences.setDarkMode(context, v) } },
                            shape = SegmentedButtonDefaults.itemShape(i, 3)
                        ) { Text(l) }
                    }
                }
            }

            // ── 关于 ──
            item {
                Text("关于", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            item {
                Column {
                    Text("一键闪记 v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                    Text("基于 ML Kit OCR · 数据仅存储在本地",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("GitHub: https://github.com/zixij644-elaborate/pickup-code-app",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            item {
                var updateStatus by remember { mutableStateOf<String?>(null) }
                var isChecking by remember { mutableStateOf(false) }

                OutlinedButton(
                    onClick = {
                        isChecking = true
                        updateStatus = null
                        scope.launch {
                            val result = checkUpdate()
                            updateStatus = result
                            isChecking = false
                        }
                    },
                    enabled = !isChecking
                ) {
                    Text(if (isChecking) "检查中..." else "检查更新")
                }
                updateStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}

private suspend fun checkUpdate(): String = withContext(Dispatchers.IO) {
    try {
        val url = java.net.URL("https://api.github.com/repos/zixij644-elaborate/pickup-code-app/releases/latest")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000

        if (conn.responseCode != 200) return@withContext "检查失败: HTTP ${conn.responseCode}"

        val response = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val json = org.json.JSONObject(response)
        val latestVersion = json.getString("tag_name").removePrefix("v")
        val currentVersion = BuildConfig.VERSION_NAME

        if (latestVersion == currentVersion) {
            "当前已是最新版本 (v$currentVersion)"
        } else {
            "发现新版本 v$latestVersion，当前 v$currentVersion"
        }
    } catch (e: Exception) {
        "检查失败: ${e.message}"
    }
}
