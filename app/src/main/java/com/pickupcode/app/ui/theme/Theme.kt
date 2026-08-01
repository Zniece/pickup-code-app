package com.pickupcode.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.pickupcode.app.preferences.AppPreferences

// 清爽蓝色系（浅色）
private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    error = Error,
    onError = OnError,
    background = Background,
    onBackground = OnSurface
)

// 灰蓝调深色（与 7E9EB5 主色统一）
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9FBBD0),
    onPrimary = Color(0xFF1D3A4C),
    primaryContainer = Color(0xFF33536B),
    onPrimaryContainer = Color(0xFFD6E3ED),
    secondary = Color(0xFFB3C1CB),
    onSecondary = Color(0xFF22323C),
    secondaryContainer = Color(0xFF39474F),
    onSecondaryContainer = Color(0xFFDDE7EC),
    surface = Color(0xFF24292E),
    onSurface = Color(0xFFE6EBEF),
    surfaceVariant = Color(0xFF3A4147),
    onSurfaceVariant = Color(0xFFC2CBD1),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    background = Color(0xFF1C2024),
    onBackground = Color(0xFFE6EBEF)
)

// 大圆角现代风格
private val AppShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PickupCodeTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val settings by AppPreferences.observe(context).collectAsState(
        initial = AppPreferences.Settings()
    )

    val systemDark = isSystemInDarkTheme()
    val isDark = when (settings.darkMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val act = view.context as? Activity ?: return@SideEffect
            val window = act.window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        content = content
    )
}
