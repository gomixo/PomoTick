package com.pomotick.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * PomoTick 主题——深色优先，Apple 设计系统配色，配合玻璃拟态风格。
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
    onSurfaceVariant = Color(0xFF8E8E93),  // design `--watch-muted`
    error = Color(0xFFFF3B30),
    onError = Color.Black,
    outline = Color(0xFF3A3A3C)
)

/**
 * 玻璃拟态扩展色板。
 */
data class ExtendedColors(
    val glassBackground: Color,
    val glassBorder: Color,
    val glowRed: Color,
    val glowBlue: Color,
    val success: Color,
    val error: Color
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        glassBackground = Color(0xFF1C1C1E).copy(alpha = 0.42f),
        glassBorder = Color.White.copy(alpha = 0.10f),
        glowRed = Color(0xFFFF3B30),
        glowBlue = Color(0xFF007AFF),
        success = Color(0xFF34C759),
        error = Color(0xFFFF3B30)
    )
}

private val PomoTickExtendedColors = ExtendedColors(
    glassBackground = Color(0xFF1C1C1E).copy(alpha = 0.42f),
    glassBorder = Color.White.copy(alpha = 0.10f),
    glowRed = Color(0xFFFF3B30),
    glowBlue = Color(0xFF007AFF),
    success = Color(0xFF34C759),
    error = Color(0xFFFF3B30)
)

/**
 * 玻璃拟态圆角体系。
 */
private val PomoTickShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun PomoTickTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtendedColors provides PomoTickExtendedColors) {
        MaterialTheme(
            colorScheme = PomoTickColorScheme,
            shapes = PomoTickShapes,
            content = content
        )
    }
}
