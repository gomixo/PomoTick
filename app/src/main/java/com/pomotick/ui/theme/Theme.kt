package com.pomotick.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * PomoTick 主题——深色优先，番茄红主色。
 *
 * 黑色背景对 OLED 屏幕省电友好（OPPO Watch 4 Pro 使用 OLED）。
 */
private val PomoTickColorScheme = darkColorScheme(
    primary = Color(0xFFFF5252),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB71C1C),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFFFAB91),
    onSecondary = Color.Black,
    tertiary = Color(0xFFFFCC80),
    onTertiary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF1C1C1C),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFCCCCCC),
    error = Color(0xFFFF5252),
    onError = Color.Black,
    outline = Color(0xFF555555)
)

@Composable
fun PomoTickTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PomoTickColorScheme,
        content = content
    )
}
