package com.pomotick.reminder

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope

/**
 * 番茄提醒铃声播放器。
 *
 * ## 设计要点
 *
 * - **系统兼容**：使用 [RingtoneManager] 取系统默认 `TYPE_ALARM` 音频，
 *   不依赖 Google Play Services、不需要自带 raw 资源。
 * - **生命周期由外部控制**：[start] 只触发一次系统铃声播放，由 [ReminderManager] 在
 *   30 秒自动停止 / 用户响应时调用 [stop] 终止。**无内部超时**——超时由
 *   上层协程统一管理。
 * - **重置播放位置**：由于每次 [start] 都新创建 [Ringtone] 实例，等价于
 *   `MediaPlayer.seekTo(0)`，天然满足"停止后重置位置"的要求。
 * - **失败安全**：取不到 ringtone URI 时 [start] 静默返回；异常被吞掉，
 *   不影响震动等其他提醒通道。
 *
 * ## 已知限制
 *
 * - Wear OS / ColorOS Watch 上 `Ringtone` 由系统播放器托管，无法做精细的
 *   进度控制；如需重置位置只能销毁重建。
 * - `Ringtone.play()` 是 fire-and-forget，每次播放会发到系统的
 *   `STREAM_ALARM` 通道，受手表端 DND / 静音策略影响。
 */
class ReminderSoundPlayer(private val context: Context) {

    private var ringtone: Ringtone? = null

    /**
     * 启动一次系统铃声播放。**必须**在合适的 [CoroutineScope] 内调用，scope 取消
     * 不会自动停止铃声（需显式调用 [stop]）。多次调用等价于"重新开始"——
     * 先停后启，避免声音叠加。
     *
     * @param scope 保留为生命周期入口参数（通常为 Service 的 lifecycleScope）。
     */
    fun start(@Suppress("UNUSED_PARAMETER") scope: CoroutineScope) {
        stop()

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: run {
                Log.w(TAG, "No alarm/notification URI available; sound disabled")
                return
            }

        val r = try {
            RingtoneManager.getRingtone(context, uri)
        } catch (e: Exception) {
            Log.e(TAG, "getRingtone failed for $uri", e)
            null
        } ?: return

        try {
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "setAudioAttributes failed", e)
        }
        ringtone = r

        try {
            r.play()
        } catch (e: Exception) {
            Log.e(TAG, "play() failed", e)
        }
    }

    /**
     * 立即停止铃声播放。调用 [Ringtone.stop] + 释放引用。
     *
     * 下次调用 [start] 时会重新创建 [Ringtone] 实例（隐式 seekTo(0)），
     * 满足"停止后重置播放位置"的要求。
     *
     * 可重复调用；调用时无 ringtone 也不会抛异常。
     */
    fun stop() {
        try {
            ringtone?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "stop() failed", e)
        } finally {
            // 引用置空 → 下次 start 重新创建 Ringtone，等价于 seekTo(0)
            ringtone = null
        }
    }

    companion object {
        private const val TAG = "PomoTick/Sound"
    }
}
