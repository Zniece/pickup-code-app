package com.pickupcode.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pickupcode.app.ui.theme.TypeCoupon
import com.pickupcode.app.ui.theme.TypeFood
import com.pickupcode.app.ui.theme.TypeParcel

/*
 * 筛选控件：Material 3 FilterChip（A 方案，扁平化，与 Sleek 主题统一）
 *   - 选中：主色填充 + 反白文字 + 对勾图标
 *   - 未选中：surface 浅底 + outlineVariant 描边 + 次级灰文字
 *   - 类型保留彩色圆点作助记（取餐蓝 / 取件紫 / 券码黄）
 */

@Composable
fun FilterChipRow(
    currentFilter: String,
    onFilterChange: (String) -> Unit
) {
    val filters = listOf(
        "all" to "全部",
        "food" to "取餐",
        "parcel" to "取件",
        "coupon" to "券码"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        filters.forEach { (key, label) ->
            val selected = currentFilter == key
            val tint = when (key) {
                "food" -> TypeFood
                "parcel" -> TypeParcel
                "coupon" -> TypeCoupon
                else -> Color.Unspecified
            }

            FilterChip(
                selected = selected,
                onClick = { onFilterChange(key) },
                label = { Text(text = label, fontSize = 13.sp) },
                leadingIcon = {
                    // 紧凑：按状态实际显示（勾 16dp / 类型圆点 7dp / 空），不撑固定槽位
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    } else if (tint != Color.Unspecified) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(tint, CircleShape)
                        )
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
