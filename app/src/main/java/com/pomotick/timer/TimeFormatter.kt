package com.pomotick.timer

import java.util.Locale
import kotlin.math.max

/**
 * 把毫秒格式化为可读字符串。
 *
 * - < 1 小时：`mm:ss`
 * - >= 1 小时：`h:mm:ss`
 */
object TimeFormatter {

    /**
     * 格式化为倒计时显示（"mm:ss" 或 "h:mm:ss"）。
     *
     * @param millis 剩余毫秒
     */
    fun formatRemaining(millis: Long): String {
        val safe = max(0L, millis)
        val totalSeconds = safe / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * 格式化为"X 小时 Y 分钟"（用于统计页）。
     */
    fun formatDuration(millis: Long): String {
        val safe = max(0L, millis)
        val totalMinutes = safe / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L

        return when {
            hours == 0L -> "${minutes}m"
            minutes == 0L -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }
}
