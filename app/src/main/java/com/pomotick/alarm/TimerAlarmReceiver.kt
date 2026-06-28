package com.pomotick.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pomotick.timer.TimerRunState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * v0.2.1: 息屏到点唤醒 receiver。
 *
 * - 静态注册（Manifest 中 `android:exported="false"`，带 action intent-filter）
 * - 系统 alarm 进程通过 [TimerAlarmScheduler] 创建的 PendingIntent 触发本 Receiver
 * - **不**读 DataStore——直接调 [com.pomotick.data.TimerRepository.handleEvent] 提交 `OnTick`
 * - 运行时状态由 Repository 持有（[com.pomotick.PomoTickApp.onCreate] 同步 bootstrap）
 *
 * ## 完整流程
 *
 * ```
 * 系统 → AlarmManager → TimerAlarmReceiver.onReceive (goAsync)
 *   → repo.currentRuntime.value (== RUNNING，已 bootstrap)
 *   → TimerForegroundService.startAlarmWakeup(app)   // 拉起 1001 通知
 *   → repo.handleEvent(TimerEvent.OnTick(now))       // Engine 自动转 RINGING
 *     → executeEffects([SaveRuntime, StartForegroundService, StartReminder, ...])
 *     → Service.handleEffect(StartReminder)          // 启动震动+铃声+1002 通知
 * ```
 *
 * ## Edge cases（状态守卫与注释完全一致：仅 RUNNING 提交 OnTick）
 *
 * - `currentRuntime == null`：App 冷启 + DataStore 还没 bootstrap 完成（极小概率，
 *   因为 PomoTickApp.onCreate 已同步读）。放弃并 Log。
 * - `currentRuntime.runState == PAUSED`：targetEnd 已冻结；alarm 不应触发；放弃。
 * - `currentRuntime.runState == RINGING`：已被 service 接管；alarm 是早前入队的；
 *   放弃。
 * - `currentRuntime.runState == IDLE / FINISHED`：无 targetEnd；alarm 应已 cancel；
 *   放弃并 Log（异常路径）。
 */
class TimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TimerAlarmScheduler.ACTION_TIMER_FIRE) {
            Log.w(TAG, "received unknown action: ${intent.action}")
            return
        }
        val pending = goAsync()
        val app = context.applicationContext as com.pomotick.PomoTickApp
        val scope = app.appScope

        scope.launch(ioDispatcher) {
            try {
                val repo = app.repository
                // v0.2.1: 不读 DataStore——Repository 已在 PomoTickApp.onCreate 同步 bootstrap。
                val current = repo.currentRuntime.value
                if (current == null) {
                    Log.w(TAG, "alarm fired but Repository has no runtime " +
                            "(cold start race?); ignoring")
                    return@launch
                }
                // 仅 RUNNING 才提交 OnTick。其他状态一律放弃（与文档 Edge cases 完全对齐）：
                // - PAUSED    → targetEnd 已冻结
                // - RINGING   → 已被 service 接管
                // - IDLE      → 无 targetEnd（异常路径）
                // - FINISHED  → 已完成（异常路径）
                if (current.runState != TimerRunState.RUNNING) {
                    Log.w(TAG, "alarm fired in state=${current.runState}; ignoring")
                    return@launch
                }
                Log.d(TAG, "alarm fired: submitting OnTick " +
                        "(state=${current.runState}, " +
                        "targetEnd=${current.targetEndAtEpochMillis})")
                // v0.2.1: 用 startAlarmWakeup 而不是 start —— 让 Service onStartCommand
                // 知道这是 alarm 唤起的，重置常驻通知节流状态，立即发 1001 通知。
                com.pomotick.service.TimerForegroundService.startAlarmWakeup(app)
                // 把 OnTick 交给 Engine：自动 RUNNING→RINGING + 发 StartReminder effect
                repo.handleEvent(
                    com.pomotick.timer.TimerEvent.OnTick(System.currentTimeMillis())
                )
            } catch (e: Exception) {
                Log.e(TAG, "alarm handler failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PomoTick/AlarmReceiver"
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    }
}
