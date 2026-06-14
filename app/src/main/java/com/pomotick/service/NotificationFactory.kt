package com.pomotick.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pomotick.MainActivity
import com.pomotick.R
import com.pomotick.timer.TimeFormatter
import com.pomotick.timer.TimerPhase

/**
 * 通知构建器 + Channel 管理。
 *
 * 三个 Channel：
 * - [CHANNEL_ID_TIMER] 常驻通知（IMPORTANCE_LOW）
 * - [CHANNEL_ID_REMINDER] 到点提醒（IMPORTANCE_HIGH）
 */
object NotificationFactory {

    const val CHANNEL_ID_TIMER = "pomotick_timer"
    const val CHANNEL_ID_REMINDER = "pomotick_reminder_silent_v2"

    const val NOTIFICATION_ID_TIMER = 1001
    const val NOTIFICATION_ID_REMINDER = 1002

    /**
     * 确保 Channel 已创建（首次启动时调用）。
     */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val timerChannel = NotificationChannel(
            CHANNEL_ID_TIMER,
            context.getString(R.string.notif_channel_timer),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_timer_desc)
            setShowBadge(false)
        }

        val reminderChannel = NotificationChannel(
            CHANNEL_ID_REMINDER,
            context.getString(R.string.notif_channel_reminder),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_reminder_desc)
            enableVibration(false)  // 震动由 VibrationHelper 控制
            setShowBadge(true)
        }

        manager.createNotificationChannels(listOf(timerChannel, reminderChannel))
    }

    /**
     * 构建"专注中 / 休息中"常驻通知。
     */
    fun buildOngoing(
        context: Context,
        remainingMs: Long,
        phase: TimerPhase
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(
            when (phase) {
                TimerPhase.FOCUS -> R.string.notif_running_focus
                TimerPhase.SHORT_BREAK -> R.string.notif_running_short_break
                TimerPhase.LONG_BREAK -> R.string.notif_running_long_break
            }
        )
        val text = TimeFormatter.formatRemaining(remainingMs)

        return NotificationCompat.Builder(context, CHANNEL_ID_TIMER)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(
                /* max = */ 100,
                /* progress = */ progressPercent(remainingMs, phase, context),
                /* indeterminate = */ false
            )
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * 构建"到点提醒"通知（高优先级 + **FullScreenIntent 唤醒屏幕**）。
     *
     * 替代废弃的 `PowerManager.SCREEN_BRIGHT_WAKE_LOCK`。
     * `setFullScreenIntent` 在息屏状态下会自动唤醒屏幕并弹出 Activity，
     * 是 Wear OS 上推荐的"亮屏提醒"做法（API 30+ 完全支持）。
     */
    fun buildRinging(context: Context, phase: TimerPhase): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(
            when (phase) {
                TimerPhase.FOCUS -> R.string.notif_ring_focus_done
                TimerPhase.SHORT_BREAK, TimerPhase.LONG_BREAK -> R.string.notif_ring_break_done
            }
        )
        val text = context.getString(R.string.notif_tap_to_respond)

        return NotificationCompat.Builder(context, CHANNEL_ID_REMINDER)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            // 息屏时弹出 Activity（替代废弃的 wake lock）
            .setFullScreenIntent(pendingIntent, /* highPriority = */ true)
            .build()
    }

    private fun progressPercent(remainingMs: Long, phase: TimerPhase, context: Context): Int {
        // 用设置中的计划时长估算进度（不够精确但够"看起来在跑"）
        val plannedMs = when (phase) {
            TimerPhase.FOCUS -> 25L * 60L * 1000L
            TimerPhase.SHORT_BREAK -> 5L * 60L * 1000L
            TimerPhase.LONG_BREAK -> 15L * 60L * 1000L
        }
        val elapsed = (plannedMs - remainingMs).coerceIn(0L, plannedMs)
        return ((elapsed * 100L) / plannedMs).toInt()
    }
}
