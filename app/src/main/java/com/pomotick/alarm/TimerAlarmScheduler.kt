package com.pomotick.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.pomotick.MainActivity

/**
 * v0.2.1+ (OPPO fix): AlarmManager 封装。
 *
 * **核心职责**：在协程 tick 之外，提供"息屏/doze 下准点唤醒"的能力。
 *
 * ## 用法（同 v0.2.1）
 *
 * - 在 [com.pomotick.data.TimerRepository.handleEvent] 末尾，根据 `newState.runState` 调
 *   `schedule(targetEndAtEpochMillis)` 或 `cancel()`
 * - 在 [com.pomotick.PomoTickApp.reregisterAlarmFromRuntime] / [com.pomotick.ui.TimerViewModel.onAppStart]
 *   重建 alarm 时调 `schedule()`
 *
 * ## v0.2.1+ 关键变更
 *
 * ### 1. 改用 `setAlarmClock` 主路径（替代 `setExactAndAllowWhileIdle`）
 *
 * **OPPO Watch / ColorOS 11** 的第三方 wakeup alarm 在 balance 模式 + 息屏时被功耗策略降级
 * 为非唤醒——logcat 证据：
 * - `convert thirdapp(com.pomotick.debug) wakeup alarm(tag:null) to non-wakeup when balance!`
 * - `forbin thirdapp(com.pomotick.debug) set wakeup alarm(tag:null)when balance and screen off!`
 * - `Forbid delivering pending non wakeup alarm`
 *
 * 改用 [AlarmManager.setAlarmClock]——被 Android 系统识别为"用户可见的 alarm clock 事件"，
 * 不受该 OPPO 功耗策略影响，符合 OPPO 文档"闹钟类应用，仅允许用户设置的唤醒 alarm"规则。
 *
 * - API 21+ 通用：`setAlarmClock`
 * - **不需要** SCHEDULE_EXACT_ALARM 权限
 * - 副作用：系统 Clock UI 可能显示"下一个闹钟"——这是预期行为
 *
 * ### 2. 去重避免重复注册（修复 logcat 频繁记录）
 *
 * `TimerRepository.syncAlarm(state)` 在每次 `handleEvent` 末尾调用，**包括每 2s tick 的
 * OnTick 事件**。即使 `targetEnd` 未变，调 `setAlarmClock` 也会在系统侧重新调度
 * （PendingIntent 替换），导致 logcat 看到重复的 `set wakeup alarm` 记录。
 *
 * 修复：在 Scheduler 实例上记录 `scheduledTargetEndAt`，相同 target 直接 return；
 * `cancel()` 时清空；新 target 时覆盖并更新记录。
 *
 * ### 3. v0.2.1++ OPPO action-tag experiment（关键补丁）
 *
 * 在 `setAlarmClock` 之上还有一层 OPPO BmPowerManager 过滤：logcat 显示
 * `wakeup alarm(tag:null)`——`tag` 字段对应 PendingIntent intent 的 component name。
 *
 * 假设：通过把 `Intent.setClass(...)` 改为 `Intent(ACTION).setPackage(...)` + Manifest
 * receiver 加 `<intent-filter><action>`，让 AlarmManager 提取 tag 时拿到 action 而非 null。
 *
 * 实施：
 * - `AndroidManifest.xml`：`<receiver android:name=".alarm.TimerAlarmReceiver" exported=false>`
 *   加 `<intent-filter><action android:name="com.pomotick.action.TIMER_FIRE"/></intent-filter>`
 * - [operationPendingIntent]：从 `Intent(context, TimerAlarmReceiver::class.java)` 改为
 *   `Intent(ACTION_TIMER_FIRE)` + `setPackage(packageName)`
 *
 * 安全保证：
 * - `setPackage(packageName)` 限定本包解析
 * - `exported=false` 阻止外部 App 直接发送 broadcast
 * - [com.pomotick.alarm.TimerAlarmReceiver.onReceive] 仍校验 `intent.action`
 *
 * 验收（§12 真机 / logcat）：
 * - 通过：logcat `wakeup alarm(tag=*walarm*:com.pomotick.action.TIMER_FIRE)`
 * - 失败：logcat 仍显示 `wakeup alarm(tag:null)`——回退方案见
 *   `pomotick-oppo-alarm-action-tag-experiment-plan.md` §3
 *
 * ## PendingIntent 设计
 *
 * - `operationPendingIntent`（requestCode=0）：广播到 [TimerAlarmReceiver]，固定 action +
 *   `FLAG_UPDATE_CURRENT` + `FLAG_IMMUTABLE`，复用同一 slot
 * - `showPendingIntent`（requestCode=1）：打开 [MainActivity]，供系统 Clock UI "下一个闹钟"
 *   点击使用；`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP`
 */
class TimerAlarmScheduler(private val context: Context) {

    private val alarmManager: AlarmManager =
        context.getSystemService(AlarmManager::class.java)
            ?: throw IllegalStateException("AlarmManager unavailable on this device")

    /**
     * 已注册的 `targetEndAt`（wall-clock 毫秒）。`null` 表示当前未注册。
     *
     * - `schedule(newTarget)`：`scheduled != null && scheduled == newTarget` 时跳过；
     *   否则调 `setAlarmClock` 并更新记录
     * - `cancel()`：调 `alarmManager.cancel` 并置 null
     *
     * 用 `@Volatile` 保证跨线程可见——[cancel] 也可能在 Service / Receiver 线程调用。
     */
    @Volatile
    private var scheduledTargetEndAt: Long? = null

    /**
     * 注册 alarm 到 [targetEndAtEpochMillis] 准点触发。
     *
     * **去重**：如果上次注册的 target 与新 target 相同，直接返回——避免每 tick 重复
     * 调 `setAlarmClock` 导致 logcat 噪声和系统调度开销。
     *
     * **主路径**：`setAlarmClock`（API 21+，OPPO 友好的"用户可见 alarm clock"语义）。
     *
     * @param targetEndAtEpochMillis 到点时间（wall clock 毫秒）
     */
    fun schedule(targetEndAtEpochMillis: Long) {
        if (shouldSkipSchedule(scheduledTargetEndAt, targetEndAtEpochMillis)) {
            Log.d(TAG, "alarmClock already scheduled for $targetEndAtEpochMillis; " +
                    "skip (dedup hit)")
            return
        }

        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(targetEndAtEpochMillis, showPendingIntent()),
            operationPendingIntent()
        )
        scheduledTargetEndAt = targetEndAtEpochMillis

        val secsAway = (targetEndAtEpochMillis - System.currentTimeMillis()) / 1000
        Log.d(TAG, "registered alarmClock for $targetEndAtEpochMillis " +
                "(in ${secsAway}s, setAlarmClock path)")
    }

    /**
     * 取消当前 alarm。幂等——未注册状态调无副作用。
     */
    fun cancel() {
        alarmManager.cancel(operationPendingIntent())
        scheduledTargetEndAt = null
        Log.d(TAG, "alarmClock cancelled")
    }

    /**
     * UI 用：检测是否可以注册精确闹钟。
     *
     * 注：v0.2.1+ 改用 `setAlarmClock` 后**不再依赖**此权限——但保留 API 以兼容
     * [com.pomotick.ui.screens.SettingsScreen] 已有的"精确闹钟未授权"提示横幅；
     * 当前 minSdk=30 (< S) 始终返回 true。
     */
    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()

    /**
     * 操作 intent：广播到 [TimerAlarmReceiver]，准点触发时由系统唤起。
     *
     * **v0.2.1++ OPPO action-tag experiment**：从"显式 component"改为"action + package"。
     *
     * 原方案：
     * ```kotlin
     * val intent = Intent(context, TimerAlarmReceiver::class.java).apply {
     *     action = ACTION_TIMER_FIRE
     *     setPackage(context.packageName)
     * }
     * ```
     * logcat 显示 `wakeup alarm(tag:null)`，被 OPPO BmPowerManager 在 balance 模式下降级。
     *
     * 现方案（与 [TimerAlarmScheduler] 顶部注释、Manifest 中 receiver intent-filter 配合）：
     * ```kotlin
     * val intent = Intent(ACTION_TIMER_FIRE).apply {
     *     setPackage(context.packageName)
     * }
     * ```
     * 期望 logcat 变为 `wakeup alarm(tag=*walarm*:com.pomotick.action.TIMER_FIRE)`。
     *
     * 安全保证：
     * - `setPackage(packageName)` 限定本包内解析
     * - Manifest `exported=false` 阻止外部 App 直接发送 broadcast
     * - [TimerAlarmReceiver.onReceive] 仍校验 action
     */
    private fun operationPendingIntent(): PendingIntent {
        val intent = Intent(ACTION_TIMER_FIRE).apply {
            // 限定到本包；Manifest intent-filter 内的 action 完成 receiver 路由
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            /* requestCode = */ REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 展示 intent：系统 Clock UI 显示"下一个闹钟"时点击触发，打开 [MainActivity]。
     */
    private fun showPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            /* requestCode = */ REQUEST_CODE_SHOW,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        /** 静态注册的 Receiver action（与 Manifest 中 receiver 的 class 对应） */
        const val ACTION_TIMER_FIRE = "com.pomotick.action.TIMER_FIRE"

        /** operation intent 的固定 requestCode——复用同一 AlarmManager slot */
        // 非 `const` 保证 reflection 可读到字段（const 会被 Kotlin 编译器 inline 到调用点）
        private val REQUEST_CODE = 0

        /** show intent 的固定 requestCode——与 operation 不同避免 PendingIntent.equals 误判 */
        private val REQUEST_CODE_SHOW = 1

        private const val TAG = "PomoTick/Alarm"

        /**
         * v0.2.1+ (OPPO fix) 纯函数：判断给当前已注册的 [scheduledTarget] 和新的 [newTarget]，
         * 是否应跳过 schedule 调用（去重）。
         *
         * - `scheduledTarget == null` → false（首次注册，必须调）
         * - `scheduledTarget == newTarget` → **true**（去重命中）
         * - `scheduledTarget != newTarget` → false（target 变化，必须重新注册）
         *
         * 抽出供单测验证。`internal` 可见性仅供同 module 的 test 访问。
         */
        @JvmStatic
        internal fun shouldSkipSchedule(scheduledTarget: Long?, newTarget: Long): Boolean =
            scheduledTarget != null && scheduledTarget == newTarget
    }
}