package com.pomotick.reminder

import android.util.Log
import com.pomotick.timer.TimerPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 震动 + 铃声统一提醒控制。
 *
 * ## v0.2 节奏（§3）
 *
 * - **首次提醒**：进入 RINGING 立即触发（震动 + 铃声，铃声受 [ReminderSettings.ringtoneEnabled] 控制）
 * - **30 秒自动停止**：若用户在 [AUTO_STOP_MS] 毫秒内未响应，
 *   自动取消震动 + 停止铃声。**界面仍停留在 RINGING 等待用户处理**。
 * - **统一停止入口**：[stop] 供用户响应 / 通知 Action / 切下一阶段共用。
 *
 * ## §4 扩展点
 *
 * v0.2 §4 要求"3min 后 1 次 15s 重复"——这是 [ReminderManager] 的另一段
 * 调度逻辑（目前未实现），将由 [Service] 或 [Repository] 触发；本类只负责
 * 单次提醒的生命周期编排，**与重复提醒解耦**。
 *
 * ## 线程模型
 *
 * - [start] 启动一个 SupervisorJob 子协程负责"震动 + 铃声启动 + 30s 计时"
 * - 协程 `finally` 块负责统一清理（**不依赖外部调用 stop**）
 * - 外部 [stop] 通过 `job.cancel()` 触发 `finally`，**幂等**——重复调用安全
 */
class ReminderManager(
    private val vib: VibrationHelper,
    private val sound: ReminderSoundPlayer,
    private val settingsProvider: suspend () -> ReminderSettings
) {

    private var job: Job? = null

    /**
     * 启动提醒。
     *
     * @param scope 用于启动协程的作用域（通常为 Service 的 lifecycleScope）
     * @param phase 阶段（决定震动波形）
     * @param durationMs 自动停止时长（毫秒）。默认 [AUTO_STOP_MS]=30s；
     *                   v0.2 §4 重复提醒时由调用方传入 [REPEAT_DURATION_MS]=15s。
     */
    fun start(
        scope: CoroutineScope,
        phase: TimerPhase,
        durationMs: Long = AUTO_STOP_MS
    ) {
        stop()
        job = scope.launch {
            try {
                val settings = settingsProvider()
                val strength = settings.strength
                val ringtoneEnabled = settings.ringtoneEnabled

                // 1. 震动——strength=0 时 VibrationHelper 内部返回，不震动
                vib.vibrateFor(phase, strength)

                // 2. 铃声——受 RINGTONE_ENABLED 控制；sound 内部静默处理取不到 URI 的情况
                if (ringtoneEnabled) {
                    sound.start(this)
                } else {
                    Log.d(TAG, "Ringtone disabled in settings; vibration only")
                }

                // 3. durationMs 后自动停止（首次 30s / 重复 15s）
                delay(durationMs)
                Log.d(TAG, "Auto-stop triggered after ${durationMs}ms without user response")
            } finally {
                // 统一清理——自动超时 / 外部 stop() / 协程作用域取消都会进入这里
                vib.cancel()
                sound.stop()
            }
        }
    }

    /**
     * 立即停止提醒（用户响应 / 放弃 / 通知 Action / 切到下一阶段时调用）。
     *
     * 幂等：多次调用安全；无活跃 job 时只清理底层组件。
     */
    fun stop() {
        job?.cancel()
        job = null
        // 即便 job 已经被 cancel()，finally 块可能尚未执行；这里冗余清理
        // 是为了避免"job 在 finally 之前被 GC"导致的悬挂资源。
        vib.cancel()
        sound.stop()
    }

    companion object {
        private const val TAG = "PomoTick/Reminder"

        /**
         * 第一次提醒自动停止超时（毫秒）。用户在此时长内未响应，
         * 提醒自动结束但屏幕仍停在 RINGING 等待用户处理。
         */
        const val AUTO_STOP_MS = 30_000L

        /**
         * v0.2 §4: 重复提醒持续时长（毫秒）。
         *
         * 与 [AUTO_STOP_MS] 的区别：重复提醒是"用户已忽略 3 分钟后再次响"，持续更短。
         * 由 [com.pomotick.service.TimerForegroundService.handleEffect] 在收到
         * `StartReminder` 时根据 `state.repeatReminderFired` 决定传入值。
         */
        const val REPEAT_DURATION_MS = 15_000L
    }
}

/**
 * 提醒设置快照。
 *
 * @property ringtoneEnabled v0.2 新增——是否在 RINGING 状态播放铃声
 * @property strength 震动强度（0=关/1=弱/2=强）
 */
data class ReminderSettings(
    val ringtoneEnabled: Boolean,
    val strength: Int
) {
    companion object {
        val DEFAULT = ReminderSettings(ringtoneEnabled = true, strength = 2)
    }
}
