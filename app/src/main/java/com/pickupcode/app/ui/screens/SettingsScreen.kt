package com.pickupcode.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val s by AppPreferences.observe(ctx).collectAsState(initial = AppPreferences.Settings())
    var apiUrl by remember { mutableStateOf(s.apiBaseUrl) }
    var apiKey by remember { mutableStateOf(s.apiKey) }
    var apiModel by remember { mutableStateOf(s.apiModel) }
    var keyVisible by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {

            item { Text("识别灵敏度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { Text("阈值越低越宽松，越高越严格", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(value = s.confidenceThreshold, onValueChange = { scope.launch(Dispatchers.IO) { AppPreferences.setConfidenceThreshold(ctx, it) } },
                    valueRange = 0.1f..0.8f, modifier = Modifier.weight(1f))
                Text("${(s.confidenceThreshold * 100).roundToInt()}%", modifier = Modifier.padding(start = 8.dp)) } }
            item { HorizontalDivider() }

            item { Text("识别类型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { Switch("🥤 取餐码", checked = s.enableFoodCodes) { scope.launch(Dispatchers.IO) { AppPreferences.setEnableFood(ctx, it) } } }
            item { Switch("📦 取件码", checked = s.enableParcelCodes) { scope.launch(Dispatchers.IO) { AppPreferences.setEnableParcel(ctx, it) } } }
            item { HorizontalDivider() }

            item { Text("🤖 AI 识别", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { Switch("启用 AI 识别", sub = if (s.enableAI) { if (s.apiKey.isNotBlank()) "已开启" else "未配置 API Key" } else "已关闭",
                checked = s.enableAI) { scope.launch(Dispatchers.IO) { AppPreferences.setEnableAI(ctx, it) } } }

            if (s.enableAI) {
                item { OutlinedTextField(apiUrl, { apiUrl = it; scope.launch(Dispatchers.IO) { AppPreferences.setApiBaseUrl(ctx, it) } },
                    Modifier.fillMaxWidth(), singleLine = true, label = { Text("API 地址") }) }
                item { OutlinedTextField(apiKey, { apiKey = it; scope.launch(Dispatchers.IO) { AppPreferences.setApiKey(ctx, it) } },
                    Modifier.fillMaxWidth(), singleLine = true, label = { Text("API Key") },
                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { keyVisible = !keyVisible }) { Text(if (keyVisible) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall) } }) }
                item { OutlinedTextField(apiModel, { apiModel = it; scope.launch(Dispatchers.IO) { AppPreferences.setApiModel(ctx, it) } },
                    Modifier.fillMaxWidth(), singleLine = true, label = { Text("模型名称") }) }
            }
            item { HorizontalDivider() }

            item { Text("关于", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { Column { Text("一键闪记 v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                Text("基于 ML Kit OCR · 数据仅存储在本地", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("GitHub: https://github.com/zixij644-elaborate/pickup-code-app", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) } }
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
private fun Switch(title: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge); sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Switch(checked = checked, onCheckedChange = onChange) }
}

private suspend fun checkUpdate(): String = withContext(Dispatchers.IO) {
    try {
        val resp = java.net.URL("https://api.github.com/repos/zixij644-elaborate/pickup-code-app/releases/latest")
            .openConnection() as java.net.HttpURLConnection
        resp.requestMethod = "GET"; resp.setRequestProperty("Accept", "application/vnd.github.v3+json")
        resp.connectTimeout = 10000; resp.readTimeout = 10000
        if (resp.responseCode != 200) return@withContext "检查失败"
        val latest = org.json.JSONObject(resp.inputStream.bufferedReader().readText()).getString("tag_name").removePrefix("v")
        if (latest == BuildConfig.VERSION_NAME) "当前已是最新版本" else "发现新版本 v$latest"
    } catch (e: Exception) { "检查失败: ${e.message}" }
}
