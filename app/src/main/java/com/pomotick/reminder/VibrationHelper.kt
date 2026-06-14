package com.pomotick.reminder

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.pomotick.timer.TimerPhase

/**
 * 震动 API 封装。
 *
 * **API 兼容策略**：
 * - API 31+（Android 12）：使用 [VibratorManager.defaultVibrator]
 * - API 30（默认路径）：使用 `Context.VIBRATOR_SERVICE`
 *
 * 两种路径统一通过 [VibrationEffect.createWaveform] 实现（AGENTS.md 强制）。
 */
class VibrationHelper(private val context: Context) {

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator ?: fallback()
        } else {
            fallback()
        }
    }

    @Suppress("DEPRECATION")
    private fun fallback(): Vibrator =
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    /**
     * 按 phase 触发震动。
     *
     * @param phase 当前阶段（决定波形）
     * @param strength 强度 0=关 / 1=弱 / 2=强
     */
    fun vibrateFor(phase: TimerPhase, strength: Int) {
        if (strength == 0) {
            // 强度 0：仅通知视觉反馈，不震动
            return
        }

        try {
            val (timings, baseAmps) = when (phase) {
                TimerPhase.FOCUS -> longArrayOf(0, 450, 160, 450, 160, 700) to intArrayOf(0, 255, 0, 255, 0, 255)
                TimerPhase.SHORT_BREAK, TimerPhase.LONG_BREAK -> longArrayOf(0, 300, 180, 300) to intArrayOf(0, 200, 0, 200)
            }

            val amplitudes = adjustAmplitude(baseAmps, strength)
            val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
            vibrator.vibrate(effect)
        } catch (e: Exception) {
            Log.e(TAG, "vibrate failed for phase=$phase strength=$strength", e)
        }
    }

    /**
     * 根据 strength 缩放振幅。
     */
    private fun adjustAmplitude(base: IntArray, strength: Int): IntArray {
        val factor = when (strength) {
            1 -> 0.5f   // 弱
            2 -> 1.0f   // 强（默认）
            else -> 0f  // 不可达（已在外层 return）
        }
        return IntArray(base.size) { i -> (base[i] * factor).toInt().coerceIn(0, 255) }
    }

    /**
     * 立即取消震动。
     */
    fun cancel() {
        try {
            vibrator.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "cancel failed", e)
        }
    }

    companion object {
        private const val TAG = "PomoTick/Vib"
    }
}
