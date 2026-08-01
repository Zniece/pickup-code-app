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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import com.pickupcode.app.data.CodeHistory
import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.learner.PatternLearner
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
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var codeConfirmed by remember { mutableStateOf(PatternLearner.isCodeConfirmed(ctx, item.id)) }
    var sourceConfirmed by remember { mutableStateOf(PatternLearner.isSourceConfirmed(ctx, item.id)) }
    var addrConfirmed by remember { mutableStateOf(PatternLearner.isAddrConfirmed(ctx, item.id)) }
    var codeIncorrect by remember { mutableStateOf(PatternLearner.isCodeIncorrect(ctx, item.id)) }
    var sourceIncorrect by remember { mutableStateOf(PatternLearner.isSourceIncorrect(ctx, item.id)) }
    var addrIncorrect by remember { mutableStateOf(PatternLearner.isAddrIncorrect(ctx, item.id)) }

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
            if (item.isActive) {
                InlineConfirm("码值正确", confirmed = codeConfirmed, incorrect = codeIncorrect,
                    onCorrect = {
                        codeConfirmed = true
                        PatternLearner.setCodeConfirmed(ctx, item.id, true)
                        scope.launch(Dispatchers.IO) {
                            PatternLearner.recordVerified(ctx, CodeExtractor.getPatternId(item.code))
                        }
                    },
                    onIncorrect = {
                        codeIncorrect = true
                        PatternLearner.setCodeIncorrect(ctx, item.id, true)
                        scope.launch(Dispatchers.IO) {
                            PatternLearner.recordCodeIncorrect(ctx, CodeExtractor.getPatternId(item.code))
                        }
                    }
                )
            }

            EditableField(label = "来源", value = item.source, displayFontSize = 18.sp,
                onSave = { onUpdated(item.copy(source = it)) })
            if (item.isActive) {
                InlineConfirm("来源正确", confirmed = sourceConfirmed, incorrect = sourceIncorrect,
                    onCorrect = {
                        sourceConfirmed = true
                        PatternLearner.setSourceConfirmed(ctx, item.id, true)
                        scope.launch(Dispatchers.IO) {
                            PatternLearner.recordSourceMatch(ctx, item.source)
                        }
                    },
                    onIncorrect = {
                        sourceIncorrect = true
                        PatternLearner.setSourceIncorrect(ctx, item.id, true)
                        scope.launch(Dispatchers.IO) {
                            PatternLearner.recordSourceIncorrect(ctx, item.source)
                        }
                    }
                )
            }

            if (item.pickupAddress.isNotBlank()) {
                EditableField(label = "取件地址", value = item.pickupAddress, displayFontSize = 16.sp,
                    onSave = { onUpdated(item.copy(pickupAddress = it)) })
                // Show geo verification badge
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.geoVerified) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text("📍 地图已验证", style = MaterialTheme.typography.labelSmall)
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                        if (item.geoFormattedAddress.isNotBlank() && item.geoFormattedAddress != item.pickupAddress) {
                            Text(
                                item.geoFormattedAddress,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
                if (item.isActive) {
                    InlineConfirm("地址正确", confirmed = addrConfirmed, incorrect = addrIncorrect,
                        onCorrect = {
                            addrConfirmed = true
                            PatternLearner.setAddrConfirmed(ctx, item.id, true)
                            scope.launch(Dispatchers.IO) {
                                PatternLearner.recordAddressVerified(ctx, item.pickupAddress, 1.0f)
                            }
                        },
                        onIncorrect = {
                            addrIncorrect = true
                            PatternLearner.setAddrIncorrect(ctx, item.id, true)
                            scope.launch(Dispatchers.IO) {
                                PatternLearner.recordAddressIncorrect(ctx, item.pickupAddress)
                            }
                        }
                    )
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
                // 不手动 recycle：Compose 的 Bitmap.asImageBitmap() 与状态共享受管理时，手动 recycle 可能造成
                // 「已回收位图仍在绘制」崩溃（Canvas 绘制期 native 已释放）。交由 GC/Compose 生命周期管理。
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

            if (item.isActive) {
                val confirmAll: () -> Unit = {
                    if (!codeConfirmed && !codeIncorrect) {
                        codeConfirmed = true
                        PatternLearner.setCodeConfirmed(ctx, item.id, true)
                        scope.launch(Dispatchers.IO) { PatternLearner.recordVerified(ctx, CodeExtractor.getPatternId(item.code)) }
                    }
                    if (!sourceConfirmed && !sourceIncorrect) {
                        sourceConfirmed = true
                        PatternLearner.setSourceConfirmed(ctx, item.id, true)
                        scope.launch(Dispatchers.IO) { PatternLearner.recordSourceMatch(ctx, item.source) }
                    }
                    if (item.pickupAddress.isNotBlank() && !addrConfirmed && !addrIncorrect) {
                        addrConfirmed = true
                        PatternLearner.setAddrConfirmed(ctx, item.id, true)
                        scope.launch(Dispatchers.IO) { PatternLearner.recordAddressVerified(ctx, item.pickupAddress, 1.0f) }
                    }
                }
                if (onMarkDone != null) {
                    Button(onClick = { confirmAll(); onMarkDone(item.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text("📦 标记已取")
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineConfirm(label: String, confirmed: Boolean, incorrect: Boolean, onCorrect: () -> Unit, onIncorrect: () -> Unit) {
    if (confirmed || incorrect) {
        Text(
            if (confirmed) "$label ✓" else "已标记错误",
            style = MaterialTheme.typography.labelSmall,
            color = if (confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCorrect, modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = onIncorrect, modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text("标记错误", style = MaterialTheme.typography.labelSmall)
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
