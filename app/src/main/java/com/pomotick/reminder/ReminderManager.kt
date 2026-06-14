package com.pomotick.reminder

import android.util.Log
import com.pomotick.timer.TimerPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 震动提醒节奏控制。
 *
 * 节奏：首次立即触发，之后每 30 秒一次，最多 [MAX_REPEATS] 次。
 * 用户响应后调用 [stop] 立即结束（震动 + 计时）。
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
     * @param phase 阶段（决定波形）
     */
    fun start(scope: CoroutineScope, phase: TimerPhase) {
        job?.cancel()
        job = scope.launch {
            val settings = settingsProvider()
            if (!settings.enabled) {
                Log.d(TAG, "Persistent reminder disabled in settings; skip")
                return@launch
            }
            val strength = settings.strength
            // 首次立即触发
            vib.vibrateFor(phase, strength)
            sound.playOnce(this)
            // 之后最多 repeat-1 次（每次间隔 REPEAT_INTERVAL_MS）
            repeat(MAX_REPEATS - 1) { i ->
                if (!isActive) return@launch
                delay(REPEAT_INTERVAL_MS)
                if (!isActive) return@launch
                Log.d(TAG, "Reminder repeat ${i + 2}/$MAX_REPEATS")
                vib.vibrateFor(phase, strength)
                sound.playOnce(this)
            }
            Log.d(TAG, "Reminder finished: $MAX_REPEATS repeats done")
        }
    }

    /**
     * 立即停止提醒（用户响应 / 放弃 / 切到下一阶段时调用）。
     */
    fun stop() {
        job?.cancel()
        job = null
        vib.cancel()
        sound.stop()
    }

    companion object {
        private const val TAG = "PomoTick/Reminder"
        const val MAX_REPEATS = 10
        const val REPEAT_INTERVAL_MS = 30_000L
    }
}

/**
 * 提醒设置快照。
 */
data class ReminderSettings(
    val enabled: Boolean,
    val strength: Int
) {
    companion object {
        val DEFAULT = ReminderSettings(enabled = true, strength = 2)
    }
}
