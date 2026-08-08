package com.pickupcode.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TimeGroupHeader(
    label: String,
    modifier: Modifier = Modifier
) {
    val weight = when (label) {
        "今天" -> FontWeight.Bold
        "昨天" -> FontWeight.Medium
        else -> FontWeight.Normal
    }
    val alpha = when (label) {
        "今天" -> 1f
        "昨天" -> 0.85f
        else -> 0.6f
    }

    Text(
        text = label,
        fontSize = 13.sp,
        fontWeight = weight,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}
