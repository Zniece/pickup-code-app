package com.pickupcode.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import com.pickupcode.app.data.CodeHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeDetailScreen(
    item: CodeHistory,
    onBack: () -> Unit,
    onUpdated: (CodeHistory) -> Unit,
    onMarkDone: ((Long) -> Unit)? = null
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("详情") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("类型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val label = if (item.type == "pickup_parcel") "取件码" else "取餐码"
                    val icon = if (item.type == "pickup_parcel") "📦" else "🥤"
                    Text("$icon $label", fontSize = 18.sp)
                }
            }

            EditableField(label = "码值", value = item.code, displayFontSize = 28.sp, displayFontWeight = FontWeight.Bold,
                onSave = { onUpdated(item.copy(code = it)) })

            EditableField(label = "来源", value = item.source, displayFontSize = 18.sp,
                onSave = { onUpdated(item.copy(source = it)) })

            if (item.pickupAddress.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("取件地址", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text("📍 ${item.pickupAddress}", fontSize = 16.sp)
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("OCR 原始文本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(item.rawTextSnippet.ifBlank { "（无原始数据）" }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
                }
            }

            var showFullscreen by remember { mutableStateOf(false) }
            if (item.screenshotPath.isNotBlank() && File(item.screenshotPath).exists()) {
                var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(item.screenshotPath) { bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(item.screenshotPath) } }
                DisposableEffect(Unit) { onDispose { bitmap?.recycle() } }
                Card(Modifier.fillMaxWidth().clickable { showFullscreen = true }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("📷 截屏（点击放大）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        bitmap?.let { bmp -> Image(bitmap = bmp.asImageBitmap(), contentDescription = "截屏",
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.FillWidth) }
                        Text("👆 点击放大查看", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                if (showFullscreen) {
                    AlertDialog(onDismissRequest = { showFullscreen = false }, confirmButton = { TextButton(onClick = { showFullscreen = false }) { Text("关闭") } },
                        text = { bitmap?.let { bmp -> Image(bitmap = bmp.asImageBitmap(), contentDescription = "截屏全屏", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) } })
                }
            }

            Text(formatTimestamp(item.timestamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (item.isActive && onMarkDone != null) {
                Button(onClick = { onMarkDone(item.id) }, modifier = Modifier.fillMaxWidth()) { Text("✓ 标记已取") }
            }
        }
    }
}

@Composable
private fun EditableField(label: String, value: String, displayFontSize: androidx.compose.ui.unit.TextUnit,
                          displayFontWeight: FontWeight? = null, onSave: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var editedValue by remember(value) { mutableStateOf(value) }
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            if (editing) {
                OutlinedTextField(editedValue, { editedValue = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { editing = false }) { Text("取消") }
                    TextButton(onClick = { editing = false; scope.launch { onSave(editedValue) } }) { Text("保存") } }
            } else {
                Text(value, fontSize = displayFontSize, fontWeight = displayFontWeight)
                TextButton(onClick = { editing = true; editedValue = value }) { Text("编辑") }
            }
        }
    }
}

private val DETAIL_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private fun formatTimestamp(epochMillis: Long): String {
    return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()).format(DETAIL_TIMESTAMP_FORMATTER)
}
