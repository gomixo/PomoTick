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
import com.pomotick.system.ActivityWakeupHelper
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
     * 通知 Action / Intent 动作常量。RINGING 通知的"停止"按钮使用此 action
     * 触发 Service 的 [com.pomotick.service.TimerForegroundService.onStartCommand]，
     * 由 Service 把事件转给 [com.pomotick.data.TimerRepository]，最终走
     * `StopRingingAndPrepareNext` 事件进入 Engine（与 UI 按钮同入口）。
     */
    const val ACTION_STOP_RINGING = "com.pomotick.action.STOP_RINGING"

    /**
     * v0.2.1: [com.pomotick.alarm.TimerAlarmReceiver] 唤起 Service 时使用的 action。
     *
     * 由 Receiver 在 `Context.startForegroundService(intent)` 中设置，让 Service
     * `onStartCommand` 知道"这次是被 alarm 唤起的"，重置常驻通知节流状态，
     * 立即发 1001 通知（不再 30s 节流延迟）。
     */
    const val ACTION_ALARM_WAKEUP = "com.pomotick.action.ALARM_WAKEUP"

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
     * 构建"到点提醒"通知（高优先级 + **FullScreenIntent 唤醒屏幕** + **停止 Action**）。
     *
     * 替代废弃的 `PowerManager.SCREEN_BRIGHT_WAKE_LOCK`。
     * `setFullScreenIntent` 在息屏状态下会自动唤醒屏幕并弹出 Activity，
     * 是 Wear OS 上推荐的"亮屏提醒"做法（API 30+ 完全支持）。
     *
     * **v0.2 新增**：通知携带"停止"Action。点击后通过 [ACTION_STOP_RINGING]
     * 触发 [com.pomotick.service.TimerForegroundService.onStartCommand]，
     * Service 把它当作 `StopRingingAndPrepareNext` 事件提交给 Engine——
     * **与 UI 屏幕上的"停止"按钮走完全相同的入口**，保证：
     * - 铃声 + 震动统一停止
     * - 状态机一致迁移到 IDLE / 准备下一阶段
     * - 不需要 Activity 起来就能处理
     */
    fun buildRinging(context: Context, phase: TimerPhase): Notification {
        val openAppIntent = ActivityWakeupHelper.intent(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            1,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "停止"Action：Service 收到 ACTION_STOP_RINGING 后转交 Engine。
        // 走 Service 而非 BroadcastReceiver 是因为我们需要 access
        // repository 协程 + reminderManager，而 Service 持有这俩单例。
        val stopIntent = Intent(context, com.pomotick.service.TimerForegroundService::class.java)
            .setAction(ACTION_STOP_RINGING)
        val stopPendingIntent = PendingIntent.getService(
            context,
            /* requestCode = */ 2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(
            when (phase) {
                TimerPhase.FOCUS -> R.string.notif_ring_focus_done
                TimerPhase.SHORT_BREAK, TimerPhase.LONG_BREAK -> R.string.notif_ring_break_done
            }
        )
        val text = context.getString(R.string.notif_tap_to_respond)
        val stopLabel = context.getString(R.string.notif_action_stop)

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
            .setContentIntent(contentIntent)
            // 息屏时弹出 Activity（替代废弃的 wake lock）
            .setFullScreenIntent(contentIntent, /* highPriority = */ true)
            // v0.2 新增：通知上"停止"按钮，绕过 Activity 直接走 Service
            .addAction(
                NotificationCompat.Action.Builder(
                    /* icon = */ 0,  // Wear OS 上不显示 icon，省略
                    stopLabel,
                    stopPendingIntent
                ).build()
            )
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
