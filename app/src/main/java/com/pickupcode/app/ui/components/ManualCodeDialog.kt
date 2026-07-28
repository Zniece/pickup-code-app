package com.pickupcode.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 手动输入取餐码/取件码的对话框
 */
@Composable
fun ManualCodeDialog(
    onDismiss: () -> Unit,
    onConfirm: (code: String, type: String, source: String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var codeType by remember { mutableStateOf("pickup_food") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动录入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 类型选择
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = codeType == "pickup_food",
                        onClick = { codeType = "pickup_food" },
                        label = { Text("取餐码") }
                    )
                    FilterChip(
                        selected = codeType == "pickup_parcel",
                        onClick = { codeType = "pickup_parcel" },
                        label = { Text("取件码") }
                    )
                }

                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    label = { Text("来源（品牌/驿站）") },
                    placeholder = { Text("如：瑞幸、菜鸟驿站") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("取餐码/取件码") },
                    placeholder = { Text("如：A-356 或 10-2-7507") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (code.isNotBlank()) {
                        val src = source.ifBlank {
                            if (codeType == "pickup_food") "手动录入" else "手动录入"
                        }
                        onConfirm(code.trim(), codeType, src)
                        onDismiss()
                    }
                },
                enabled = code.isNotBlank()
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
