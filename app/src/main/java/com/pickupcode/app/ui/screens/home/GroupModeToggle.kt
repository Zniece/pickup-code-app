package com.pickupcode.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 分组方式切换：按时间（今天/昨天/更早） / 按地址聚合。
 * 与 FilterChipRow 同风格（M3 FilterChip，选中主色填充 + 对勾）。
 */
@Composable
fun GroupModeToggle(
    mode: String,
    onModeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        listOf("time" to "⏱ 按时间", "address" to "📍 按地址").forEach { (key, label) ->
            val selected = mode == key
            FilterChip(
                selected = selected,
                onClick = { onModeChange(key) },
                label = { Text(text = label, fontSize = 13.sp) },
                leadingIcon = {
                    if (selected) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}
