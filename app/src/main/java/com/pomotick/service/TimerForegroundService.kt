package com.pomotick.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.pomotick.PomoTickApp
import com.pomotick.reminder.ReminderManager
import com.pomotick.system.ActivityWakeupHelper
import com.pomotick.system.OemDetector
import com.pomotick.timer.TimerEffect
import com.pomotick.timer.TimerEvent
import com.pomotick.timer.TimerPhase
import com.pomotick.timer.TimerRunState
import com.pomotick.timer.TimerRuntimeState
import com.pomotick.timer.remainingMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 番茄计时前台服务。
 *
 * ## 关键修复（基于 OPPO Watch 4 Pro 实测日志）
 *
 * 1. **不再重复处理 effects**：`repo.handleEvent()` 内部已通过
 *    `effectHandler → PomoTickApp.handleGlobalEffect → currentService.handleEffect()`
 *    全路由，Service 在 `handleTick` 里**不再**遍历 `result.effects`。
 *
 * 2. **常驻通知 1001 节流**：每 2s tick 都会触发 UpdateNotification effect，
 *    但 OPPO Watch 上 `can not post! key=...|1001|...` 报错。改为：
 *    - 状态变化时（开始/暂停/恢复/延长/进入 RINGING/结束）立即刷新
 *    - 运行时最多每 [ONGOING_NOTIFICATION_MIN_INTERVAL_MS] 刷新一次
 *
 * 3. **RINGING 通知 1002 只发一次**：OPPO Watch 上 `Muting recently noisy` 提示
 *    系统对重复提醒通知做了静音。改为：
 *    - 进入 RINGING 时发一次
 *    - 后续 30s 一次的 repeat 只调震动器，不重复发通知
 *    - StopReminder 时取消通知
 *
 * 4. **震动是主力**：在 OPPO Watch 4 Pro 上 `VibrationEffect.createWaveform` 稳定可用，
 *    通知退居入口引导，避免依赖系统通知声音。
 *
 * ## 方案 D 第四层：最后 60s WakeLock 保活
 *
 * ColorOS Watch 的 BmPowerManager 在最后阶段可能暂停后台 tick。
 * 在 `now + 60s` 之后，acquire 一个 `PARTIAL_WAKE_LOCK`（最大 90s），
 * 保证 Service 的 2s tick 能稳定跑完直到 timer 到点。
 * 到点 / 取消 / 离开 RUNNING 时立即 release。
 */
class TimerForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    private lateinit var reminderManager: ReminderManager

    private val repo by lazy { (application as PomoTickApp).repository }

    // ===== 节流状态 =====

    /** 上次更新常驻通知 1001 的 wall clock（用于 30s 节流） */
    @Volatile
    private var lastOngoingNotificationAtMs: Long = 0L

    /** 上次通知的 (phase|runState) 签名——变化时强制刷新，不受 30s 限制 */
    @Volatile
    private var lastOngoingNotificationKey: String = ""

    /** RINGING 通知 1002 是否已发出——每个 RINGING 周期只发一次 */
    @Volatile
    private var ringingNotificationPosted: Boolean = false

    // ===== 方案 D 第四层：WakeLock 保活 =====

    /**
     * 最后 60s 保活用的 partial wake lock。
     *
     * - 仅在 [acquireFinalWakeLock] 持有
     * - 释放条件：到点 / 暂停 / 主动 stopSelf / onDestroy / 延长后 remaining 重回 >60s
     *
     * `@Volatile` 保证跨线程可见——[releaseFinalWakeLock] 可能从主线程
     * （onDestroy / handleEffect）调用，而 acquire/check 在 serviceScope (Dispatchers.Default)。
     * `setReferenceCounted(false)` 确保多次 acquire 不会叠加计数；
     * acquire / release 用 `runCatching` 包裹，避免在 OEM 极端策略下抛异常影响主流程。
     */
    @Volatile
    private var finalWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        NotificationFactory.ensureChannels(this)

        // 向 Application 注册自身，供全局 effect handler 路由
        (application as PomoTickApp).registerService(this)

        reminderManager = ReminderManager(
            vib = com.pomotick.reminder.VibrationHelper(this),
            sound = com.pomotick.reminder.ReminderSoundPlayer(this),
            settingsProvider = {
                val snap = (application as PomoTickApp).settingsSnapshot()
                com.pomotick.reminder.ReminderSettings(
                    ringtoneEnabled = snap.ringtoneEnabled,
                    strength = snap.vibrationStrength
                )
            }
        )

        Log.d(TAG, "Service created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v0.2 P1 修复：从通知"停止"Action 进入时，走 StopRingingOnly——
        // 仅停声震、保持 RINGING、启动 §4 等待窗口（30s 等待 → 3min 窗口 → 1 次 15s 重复）。
        //
        // 用户想"完成 → 推进阶段"需要打开 App 点"知道了"按钮。
        if (intent?.action == NotificationFactory.ACTION_STOP_RINGING) {
            Log.d(TAG, "Received ACTION_STOP_RINGING from notification; submitting StopRingingOnly")
            serviceScope.launch {
                repo.handleEvent(TimerEvent.StopRingingOnly(System.currentTimeMillis()))
            }
        }

        // v0.2.1: 从 AlarmReceiver 唤起 → 强制刷新常驻通知节流状态。
        // Receiver 已经在 goAsync 里调了 repo.handleEvent(OnTick)，Engine 应该已经
        // 算出 RINGING 并发了 StartReminder effect。这里只保证"通知立即可见"。
        if (intent?.action == NotificationFactory.ACTION_ALARM_WAKEUP) {
            Log.d(TAG, "Service restarted by TimerAlarmReceiver (alarm wakeup)")
            lastOngoingNotificationAtMs = 0L
            lastOngoingNotificationKey = ""
        }

        // 立即启动前台通知（API 30+ 5 秒内必须调用 startForeground()）
        val initialState = repo.currentRuntime.value
        if (initialState != null) {
            val remaining = remainingMillis(System.currentTimeMillis(), initialState)
            startForegroundCompat(NotificationFactory.buildOngoing(this, remaining, initialState.phase))
            // 初始化节流状态：本次视为"刚更新过"
            lastOngoingNotificationAtMs = System.currentTimeMillis()
            lastOngoingNotificationKey = "${initialState.phase.name}|${initialState.runState.name}"
        } else {
            // 启动时无 runtime → 用占位通知
            startForegroundCompat(
                NotificationFactory.buildOngoing(this, 25L * 60L * 1000L, TimerPhase.FOCUS)
            )
        }

        if (tickJob == null || !tickJob!!.isActive) {
            tickJob = serviceScope.launch {
                while (isActive) {
                    val now = System.currentTimeMillis()
                    if (repo.currentRuntime.value != null) {
                        // 关键简化：只提交 OnTick，effects 由 Repository 全路由到 handleEffect
                        handleTick(now)
                        // 方案 D 第四层：检查是否需要 acquire WakeLock
                        maybeAcquireFinalWakeLock(now)
                    }
                    delay(TICK_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    /**
     * 处理一次 tick：**只**提交 OnTick 事件，副作用由 Repository 全路由到 [handleEffect]。
     *
     * 重要：此处**不再**遍历 `result.effects`——否则会在 OPPO Watch 上重复触发
     * StartReminder/UpdateNotification。
     */
    private suspend fun handleTick(now: Long) {
        repo.handleEvent(TimerEvent.OnTick(now))
    }

    /**
     * 方案 D 第四层：当 RUNNING 状态进入"最后 [FINAL_WAKE_LOCK_LEAD_MS]"窗口时，
     * acquire 一个 partial WakeLock；当 remaining 重回 > [FINAL_WAKE_LOCK_LEAD_MS]
     * 时（用户延长 timer），立即释放。
     *
     * 设计要点：
     * - 只在 RUNNING 时 acquire——其他状态不应持有 wake lock
     * - 一次性 acquire，进入窗口后即便 `targetEnd` 变化也不重复加锁
     * - 延长导致 remaining 重回 >60s 时释放，避免无谓耗电
     * - 到点 / 离开 RUNNING 时由 [releaseFinalWakeLock] 释放
     */
    private fun maybeAcquireFinalWakeLock(now: Long) {
        val runtime = repo.currentRuntime.value ?: return
        if (runtime.runState != TimerRunState.RUNNING) {
            // 不在 RUNNING → 不应持有 wake lock
            if (finalWakeLock?.isHeld == true) releaseFinalWakeLock()
            return
        }
        val remaining = runtime.targetEndAtEpochMillis - now
        if (remaining in 1..FINAL_WAKE_LOCK_LEAD_MS) {
            if (finalWakeLock?.isHeld != true) {
                acquireFinalWakeLock()
            }
        } else if (remaining > FINAL_WAKE_LOCK_LEAD_MS) {
            // remaining > 60s（用户延长 timer 或初始阶段）→ 释放 wake lock
            if (finalWakeLock?.isHeld == true) {
                releaseFinalWakeLock()
                Log.d(TAG, "released final WakeLock (remaining=${remaining}ms, outside countdown window)")
            }
        }
        // remaining <= 0 的情况：Engine 应该已经发了 StartReminder → handleEffect 里 release
    }

    /**
     * Acquire partial WakeLock——`setReferenceCounted(false)` 避免重复 acquire 叠加。
     *
     * 用 `runCatching` 包裹：OEM 在极端策略下抛 `RuntimeException` 不影响 Service 主流程。
     */
    private fun acquireFinalWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        val lock = finalWakeLock ?: pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PomoTick::FinalCountdownWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
        finalWakeLock = lock
        runCatching {
            // 90s 比 60s 留 30s 缓冲，防止 OEM 提前释放后又有几秒延迟
            lock.acquire(FINAL_WAKE_LOCK_TIMEOUT_MS)
            Log.d(TAG, "acquired final WakeLock (countdown window)")
        }.onFailure { e ->
            Log.w(TAG, "acquire final WakeLock failed: ${e.message}")
        }
    }

    /**
     * 立即释放 WakeLock——幂等。
     */
    private fun releaseFinalWakeLock() {
        val lock = finalWakeLock ?: return
        runCatching {
            if (lock.isHeld) {
                lock.release()
                Log.d(TAG, "released final WakeLock")
            }
        }.onFailure { e ->
            Log.w(TAG, "release final WakeLock failed: ${e.message}")
        }
    }

    /**
     * 由全局 effect handler 路由过来的 effect。
     *
     * 唯一执行点：Repository.effectHandler → PomoTickApp.handleGlobalEffect → currentService.handleEffect。
     */
    fun handleEffect(effect: TimerEffect) {
        when (effect) {
            is TimerEffect.StartReminder -> {
                // v0.2 P1 修复：Engine 在发送 effect 时已经算好 durationMs，
                // 不再反查 runtime。避免"effect 到达时 runtime 还没更新"的竞态。
                Log.d(TAG, "[global] Start reminder: phase=${effect.phase}, durationMs=${effect.durationMs}")
                // 进入 RINGING 即可释放 wake lock——Service tick 已不需要保活
                releaseFinalWakeLock()
                reminderManager.start(serviceScope, effect.phase, effect.durationMs)
                // OPPO/ColorOS Watch 真机修复：HeyNotification 会拦截 1002 通知，
                // 导致无法亮屏、震动被 BmNonAndroidState 丢弃。直接拉起 Activity。
                if (OemDetector.isOppoOrColorOs()) {
                    ActivityWakeupHelper.wakeUp(this)
                }
                // RINGING 通知 1002 只发一次（避免系统 Muting recently noisy）。
                // OPPO 上同时作为 Activity 拉起失败的 fallback；非 OPPO 设备为主力亮屏入口。
                if (!ringingNotificationPosted) {
                    NotificationManagerCompat.from(this).notify(
                        NotificationFactory.NOTIFICATION_ID_REMINDER,
                        NotificationFactory.buildRinging(this, effect.phase)
                    )
                    ringingNotificationPosted = true
                }
            }
            is TimerEffect.StopReminder -> {
                Log.d(TAG, "[global] Stop reminder")
                reminderManager.stop()
                // 取消 RINGING 通知
                NotificationManagerCompat.from(this).cancel(NotificationFactory.NOTIFICATION_ID_REMINDER)
                ringingNotificationPosted = false
            }
            is TimerEffect.UpdateNotification -> {
                val runtime = repo.currentRuntime.value ?: return
                if (runtime.runState == TimerRunState.IDLE) return
                // 30s 节流：相同 (phase, runState) 状态下最多每 30s 刷一次
                postOngoingNotification(runtime, effect.remainingMs, force = false)
            }
            is TimerEffect.StopForegroundService -> {
                Log.d(TAG, "Stopping foreground service")
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                releaseFinalWakeLock()
                stopSelf()
            }
            else -> Unit  // SaveRuntime / ClearRuntime / RecordSession 由 Repository 直接处理
        }
    }

    /**
     * 发布常驻通知 1001，带 30s 节流。
     *
     * @param force `true` 跳过节流（用于 onStartCommand 首次 / 状态变化场景）
     */
    private fun postOngoingNotification(state: TimerRuntimeState, remainingMs: Long, force: Boolean) {
        val key = "${state.phase.name}|${state.runState.name}"
        val now = System.currentTimeMillis()
        if (!force) {
            val elapsed = now - lastOngoingNotificationAtMs
            val sameState = key == lastOngoingNotificationKey
            if (sameState && elapsed < ONGOING_NOTIFICATION_MIN_INTERVAL_MS) {
                // 节流：跳过本次刷新
                return
            }
        }
        lastOngoingNotificationAtMs = now
        lastOngoingNotificationKey = key
        NotificationManagerCompat.from(this).notify(
            NotificationFactory.NOTIFICATION_ID_TIMER,
            NotificationFactory.buildOngoing(this, remainingMs, state.phase)
        )
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NotificationFactory.NOTIFICATION_ID_TIMER,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NotificationFactory.NOTIFICATION_ID_TIMER, notification)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        tickJob?.cancel()
        serviceScope.cancel()
        reminderManager.stop()
        // 方案 D 第四层：Service 销毁时确保 WakeLock 释放，避免悬挂
        releaseFinalWakeLock()
        // 清理通知，避免泄漏到下一轮
        NotificationManagerCompat.from(this).cancel(NotificationFactory.NOTIFICATION_ID_REMINDER)
        NotificationManagerCompat.from(this).cancel(NotificationFactory.NOTIFICATION_ID_TIMER)
        // 从 Application 注销
        (application as? PomoTickApp)?.registerService(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PomoTick/Service"
        private const val TICK_INTERVAL_MS = 2_000L

        /**
         * 常驻通知最小刷新间隔：30 秒。
         *
         * OPPO Watch 4 Pro（ColorOS Watch）实测：2s 一次触发
         * `can not post! key=...|1001|null|10078` 报错。
         * 30s 节流后稳定无报错，且分钟级精度对用户已足够。
         */
        private const val ONGOING_NOTIFICATION_MIN_INTERVAL_MS = 30_000L

        /**
         * 方案 D 第四层：在 timer 到点前 [FINAL_WAKE_LOCK_LEAD_MS] 毫秒时 acquire WakeLock，
         * 保证 Service tick 在 OEM 后台限制下不被暂停。
         *
         * 60s 留出余量：Service 的 2s tick 在 60s 内能跑 30 次，足够 Engine 准确进入 RINGING。
         */
        private const val FINAL_WAKE_LOCK_LEAD_MS = 60_000L

        /**
         * WakeLock 超时时间（90 秒）——比 [FINAL_WAKE_LOCK_LEAD_MS] 略长 30s，
         * 防止 OEM 提前释放后仍有几秒延迟导致 wake lock 提前失效。
         *
         * 90s 上限合理：PomoTick timer 至少 1 分钟，90s 不会与其他 system wake lock 冲突太久。
         */
        private const val FINAL_WAKE_LOCK_TIMEOUT_MS = 90_000L

        /**
         * 启动服务（从 ViewModel/UI 触发）。
         */
        fun start(context: Context) {
            val intent = Intent(context, TimerForegroundService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        /**
         * v0.2.1: 从 [com.pomotick.alarm.TimerAlarmReceiver] 唤起 Service。
         *
         * 与 [start] 的区别：携带 [NotificationFactory.ACTION_ALARM_WAKEUP] action，
         * 让 `onStartCommand` 知道"这次是被 alarm 唤起的"——重置常驻通知节流状态，
         * 立即发 1001 通知。
         */
        fun startAlarmWakeup(context: Context) {
            val intent = Intent(context, TimerForegroundService::class.java).apply {
                action = NotificationFactory.ACTION_ALARM_WAKEUP
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        /**
         * 停止服务（从 Repository 在收到 StopForegroundService effect 时触发）。
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, TimerForegroundService::class.java))
        }
    }
}
