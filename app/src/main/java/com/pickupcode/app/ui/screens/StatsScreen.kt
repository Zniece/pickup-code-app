package com.pickupcode.app.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pickupcode.app.learner.PatternLearner
import com.pickupcode.app.learner.PatternLearner.PatternSuggestion
import com.pickupcode.app.learner.PatternLearner.PatternStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<PatternStats?>(null) }
    var suggestions by remember { mutableStateOf<List<PatternSuggestion>>(emptyList()) }
    var learnedRules by remember { mutableStateOf<List<PatternLearner.LearnedRule>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            stats = withContext(Dispatchers.IO) { PatternLearner.getStats(context) }
            suggestions = withContext(Dispatchers.IO) { PatternLearner.getSuggestions(context) }
            learnedRules = withContext(Dispatchers.IO) { PatternLearner.getLearnedPatterns(context) }
            // Trigger auto-apply on view
            withContext(Dispatchers.IO) { PatternLearner.autoApply(context) }
        } catch (e: Exception) {
            stats = null
            Log.e("StatsScreen", "加载统计失败", e)
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("📊 识别统计") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                stats?.let { s ->
                    OverviewCard(s)
                    PatternBreakdownCard(s)
                } ?: EmptyStateMessage()

                SuggestionsCard(suggestions) { cleared ->
                    if (cleared) {
                        scope.launch {
                            suggestions = withContext(Dispatchers.IO) {
                                PatternLearner.getSuggestions(context)
                            }
                        }
                    }
                }

                LearnedRulesCard(learnedRules)
            }
        }
    }
}

@Composable
private fun OverviewCard(stats: PatternStats) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("总览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem("总扫描", stats.totalScans.toString())
                StatItem("命中", stats.attempts.toString(), MaterialTheme.colorScheme.primary)
                StatItem("未识别", stats.misses.toString(), MaterialTheme.colorScheme.error)
                StatItem("已确认", stats.verified.toString(), MaterialTheme.colorScheme.tertiary)
            }
            if (stats.totalScans > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { stats.attempts.toFloat() / stats.totalScans },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    "命中率 ${(stats.attempts * 100f / stats.totalScans).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PatternBreakdownCard(stats: PatternStats) {
    val patterns = stats.perPattern.entries.sortedByDescending { it.value }
    if (patterns.isEmpty()) return

    val maxCount = patterns.maxOf { it.value }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("模式分布", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            patterns.forEach { (id, count) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            patternLabel(id),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "$count 次",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LinearProgressIndicator(
                        progress = { count.toFloat() / maxCount },
                        modifier = Modifier.width(80.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionsCard(suggestions: List<PatternSuggestion>, onCleared: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showClearDialog by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("💡 模式建议", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (suggestions.isNotEmpty()) {
                    TextButton(onClick = { showClearDialog = true }) {
                        Text("清除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (suggestions.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "暂无建议。继续使用 App，系统会从未识别的文本中自动发现新模式。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(8.dp))
                suggestions.forEach { sug ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(sug.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "${(sug.confidence * 100).toInt()}% · ${sug.count}次",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "样本: ${sug.sampleCodes.take(3).joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                sug.proposedRegex,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除未匹配样本？") },
            text = { Text("这会删除所有未识别的 OCR 文本记录，系统将重新从零开始学习。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { PatternLearner.clearUnmatched(context) }
                    showClearDialog = false
                    onCleared(true)
                }) { Text("确认清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun EmptyStateMessage() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("暂无统计数据", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "使用 App 识别取餐码/取件码后，统计数据会在这里展示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun patternLabel(id: String): String = when (id) {
    "PREFIXED_CODE" -> "前缀匹配（取件码: XXX）"
    "THREE_SEGMENT_PARCEL" -> "三段式取件码（1-2-3456）"
    "FOUR_SEGMENT_PARCEL" -> "四段式取件码（A1-2-3-45）"
    "LETTER_TWO_SEGMENT_PARCEL" -> "两段式字母（A-1-234）"
    "LETTER_DASH_FIVE_PARCEL" -> "字母-数字（D-06003）"
    "LONG_NUMBER_PARCEL" -> "长数字（6-8位）"
    else -> id
}

@Composable
private fun LearnedRulesCard(rules: List<PatternLearner.LearnedRule>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("🧠 已学习规则", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("系统自动从未识别样本中学习并应用的新正则", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (rules.isEmpty()) {
                Text(
                    "暂无已学习规则。继续使用 App，系统会从未识别的文本中自动学习并应用新正则。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }
            rules.forEach { rule ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            rule.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            rule.regex,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Text(
                        if (rule.type == "pickup_food") "🥤" else "📦",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
