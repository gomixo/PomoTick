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
    primaryContainer = Color(0xFF3D1313), // More subtle container
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFF00BFA5), // Teal for Break
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF003730),
    onSecondaryContainer = Color(0xFF70F7E1),
    background = Color.Black,
    onBackground = Color(0xFFEEEEEE),
    surface = Color(0xFF121212), // Deep surface
    onSurface = Color(0xFFE2E2E2),
    surfaceVariant = Color(0xFF242424), // Medium surface
    onSurfaceVariant = Color(0xFFC4C4C4),
    error = Color(0xFFFF5252),
    onError = Color.Black,
    outline = Color(0xFF444444)
)

@Composable
fun PomoTickTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PomoTickColorScheme,
        content = content
    )
}
