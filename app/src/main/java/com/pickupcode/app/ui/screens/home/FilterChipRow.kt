package com.pickupcode.app.ui.screens.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pickupcode.app.ui.theme.TypeCoupon
import com.pickupcode.app.ui.theme.TypeFood
import com.pickupcode.app.ui.theme.TypeParcel

/*
 * 参照 card-preview.html 的 chip 设计：
 *   - 未选中：box-shadow: 2px 2px 5px rgba(0,0,0,0.08), -2px -2px 5px rgba(255,255,255,0.9)
 *   - 选中：  box-shadow: inset 1px 1px 3px rgba(0,0,0,0.1)
 *   - 背景：  #F3F4F6（与页面背景同色）
 *   - padding: 8px 16px, border-radius: 20px, gap: 10px
 */

private val LightHighlight = Color(0xE6FFFFFF) // rgba(255,255,255,0.9)
private val LightShadow    = Color(0x14000000) // rgba(0,0,0,0.08)
private val LightInset     = Color(0x1A000000) // rgba(0,0,0,0.1)

private val DarkHighlight = Color(0x0DFFFFFF)
private val DarkShadow    = Color(0x1A000000)
private val DarkInset     = Color(0x26000000)

@Composable
fun FilterChipRow(
    currentFilter: String,
    onFilterChange: (String) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val bg = MaterialTheme.colorScheme.background

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

            NeuChip(
                label = label,
                selected = selected,
                tint = tint,
                bg = bg,
                isDark = isDark,
                onClick = { onFilterChange(key) }
            )
        }
    }
}

@Composable
private fun NeuChip(
    label: String,
    selected: Boolean,
    tint: Color,
    bg: Color,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(100), label = "scale"
    )
    val pressOffsetY by animateFloatAsState(
        targetValue = if (isPressed) 1.dp.value else 0f,
        animationSpec = tween(100), label = "offset"
    )

    val textColor by animateColorAsState(
        targetValue = when {
            selected && tint != Color.Unspecified -> tint
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200), label = "text"
    )

    val (highlight, shadow, inset) = if (isDark) {
        Triple(DarkHighlight, DarkShadow, DarkInset)
    } else {
        Triple(LightHighlight, LightShadow, LightInset)
    }

    Box(
        modifier = Modifier
            .scale(pressScale)
            .offset(y = pressOffsetY.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = textColor,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(bg, RoundedCornerShape(20.dp))
                .drawBehind {
                    if (isPressed || selected) {
                        // 选中或按压 = 凹陷
                        drawInsetShadow(inset)
                    } else {
                        // 未选中 = 凸起
                        drawRaisedShadow(highlight, shadow)
                    }
                }
                .clip(RoundedCornerShape(20.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

// ── 凸起外阴影：2px 2px 5px 暗影 + -2px -2px 5px 高光 ──
private fun DrawScope.drawRaisedShadow(highlight: Color, shadow: Color) {
    val offsetPx = 2.dp.toPx()
    val blurPx = 5.dp.toPx()
    val cnr = CornerRadius(20.dp.value * density, 20.dp.value * density)

    drawIntoCanvas { canvas ->
        val paint = Paint()
        paint.asFrameworkPaint().maskFilter =
            android.graphics.BlurMaskFilter(blurPx, android.graphics.BlurMaskFilter.Blur.NORMAL)

        // 高光：左上
        paint.color = highlight
        canvas.drawRoundRect(
            -offsetPx, -offsetPx,
            size.width + offsetPx, size.height + offsetPx,
            cnr.x, cnr.y, paint
        )
        // 暗影：右下
        paint.color = shadow
        canvas.drawRoundRect(
            offsetPx, offsetPx,
            size.width - offsetPx, size.height - offsetPx,
            cnr.x, cnr.y, paint
        )
    }
}

// ── 凹陷内阴影：inset 1px 1px 3px ──
private fun DrawScope.drawInsetShadow(inset: Color) {
    val blurPx = 3.dp.toPx()
    val cnr = CornerRadius(20.dp.value * density, 20.dp.value * density)
    val w = size.width
    val h = size.height

    drawIntoCanvas { canvas ->
        val paint = Paint()
        paint.color = inset
        paint.asFrameworkPaint().maskFilter =
            android.graphics.BlurMaskFilter(blurPx, android.graphics.BlurMaskFilter.Blur.NORMAL)
        paint.asFrameworkPaint().style = android.graphics.Paint.Style.STROKE
        paint.asFrameworkPaint().strokeWidth = 2.dp.toPx()

        canvas.drawRoundRect(
            1.dp.toPx(), 1.dp.toPx(),
            w - 1.dp.toPx(), h - 1.dp.toPx(),
            cnr.x, cnr.y, paint
        )
    }
}
