package com.pickupcode.app.ui.screens.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.pickupcode.app.data.AppDatabase
import com.pickupcode.app.data.CodeHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        ) {
            // 提示 + 批量操作
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "已取/已删除的记录在此保留24小时",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            trashHistory.forEach { db.codeHistoryDao().restore(it.id) }
                        }
                    }) { Text("全部恢复") }
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            trashHistory.forEach { db.codeHistoryDao().deleteById(it.id) }
                        }
                    }) {
                        Text("全部清空", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

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
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    items(trashHistory, key = { it.id }) { item ->
                        TrashCard(
                            item = item,
                            onRestore = {
                                scope.launch(Dispatchers.IO) {
                                    db.codeHistoryDao().restore(item.id)
                                }
                            },
                            onDelete = {
                                scope.launch(Dispatchers.IO) {
                                    db.codeHistoryDao().deleteById(item.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrashCard(
    item: CodeHistory,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    // H8: 组合期只算一次会导致倒计时/进度条冻结；用 tick 每分钟刷新
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            tick = System.currentTimeMillis()
        }
    }
    val remaining = item.doneAt + 24 * 60 * 60 * 1000 - tick
    val progress = (remaining.toFloat() / (24 * 60 * 60 * 1000)).coerceIn(0f, 1f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = item.code,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                textDecoration = TextDecoration.LineThrough
            )
            Text(
                text = "${item.source} · ${formatTime(item.timestamp)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                text = "剩余 ${formatRemaining(item.doneAt, tick)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onRestore) { Text("恢复") }
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
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

private fun formatRemaining(doneAt: Long, now: Long): String {
    val remaining = doneAt + 24 * 60 * 60 * 1000 - now
    if (remaining <= 0) return "即将删除"
    val hours = remaining / (60 * 60 * 1000)
    val minutes = (remaining % (60 * 60 * 1000)) / (60 * 1000)
    return if (hours > 0) "${hours}小时${minutes}分" else "${minutes}分钟"
}
