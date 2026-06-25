package com.pomotick.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * PomoTick 主题——深色优先，Apple 设计系统配色。
 *
 * - primary: Apple System Red #FF3B30（专注色）
 * - secondary: Apple System Blue #007AFF（休息色）
 * - tertiary: Apple Orange #FF9500（长休息/特殊状态）
 * - 纯黑背景，OLED 屏幕省电友好（OPPO Watch 4 Pro）
 * - surface / outline 等对齐 Apple dark mode 色板
 */
private val PomoTickColorScheme = darkColorScheme(
    primary = Color(0xFFFF3B30),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3D1313),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFF007AFF),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF003730),
    onSecondaryContainer = Color(0xFF70F7E1),
    tertiary = Color(0xFFFF9500),
    onTertiary = Color.White,
    background = Color.Black,
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFE5E5EA),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFAEAEB2),
    error = Color(0xFFFF3B30),
    onError = Color.Black,
    outline = Color(0xFF3A3A3C)
)

@Composable
fun PomoTickTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PomoTickColorScheme,
        content = content
    )
}
