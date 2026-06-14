package com.pomotick

import android.app.Application
import android.content.Context
import com.pomotick.data.AppDatabase
import com.pomotick.data.RuntimeStateStore
import com.pomotick.data.SettingsSnapshot
import com.pomotick.data.SettingsStore
import com.pomotick.data.TimerRepository
import com.pomotick.data.TimerSessionDao
import com.pomotick.data.pomotickDataStore
import com.pomotick.timer.TimerEffect
import com.pomotick.timer.TimerRunState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * 应用入口 + 简易 Service Locator（**无 Hilt**）。
 *
 * 持有全局单例的 [TimerRepository] / [SettingsStore] / [RuntimeStateStore]，
 * 并维护一个对当前活跃 [com.pomotick.service.TimerForegroundService] 的弱引用，
 * 供全局 effect handler 路由 [TimerEffect.StartReminder] / [TimerEffect.StopReminder] /
 * [TimerEffect.UpdateNotification]。
 */
class PomoTickApp : Application() {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob())

    private val db: AppDatabase by lazy { AppDatabase.get(this) }
    val dao: TimerSessionDao by lazy { db.timerSessionDao() }
    val settingsStore: SettingsStore by lazy { SettingsStore.from(this) }
    val runtimeStore: RuntimeStateStore by lazy {
        RuntimeStateStore(applicationContext.pomotickDataStore)
    }

    val repository: TimerRepository by lazy {
        TimerRepository(
            dao = dao,
            runtime = runtimeStore,
            settings = settingsStore,
            externalScope = appScope,
            clock = { System.currentTimeMillis() },
            effectHandler = { effect -> handleGlobalEffect(effect) }
        )
    }

    private val _settingsSnapshot = MutableStateFlow(SettingsSnapshot.DEFAULT)

    /**
     * 当前活跃 [com.pomotick.service.TimerForegroundService] 引用。
     *
     * 由 Service 在 onCreate 注册、onDestroy 注销；handleGlobalEffect 据此路由
     * Service-only effects（StartReminder / StopReminder / UpdateNotification）。
     *
     * 用 @Volatile 保证跨线程可见性（UI 线程可能调用 handleGlobalEffect）。
     */
    @Volatile
    private var currentService: com.pomotick.service.TimerForegroundService? = null

    fun registerService(service: com.pomotick.service.TimerForegroundService?) {
        currentService = service
    }

    override fun onCreate() {
        super.onCreate()
        bootstrap()
    }

    /**
     * 初始化设置快照缓存 + 监听变化。
     */
    private fun bootstrap() {
        appScope.launch {
            _settingsSnapshot.value = SettingsSnapshot(
                focusMinutes = settingsStore.focusMinutes.first(),
                shortBreakMinutes = settingsStore.shortBreakMinutes.first(),
                longBreakMinutes = settingsStore.longBreakMinutes.first(),
                vibrationStrength = settingsStore.vibrationStrength.first(),
                persistentReminder = settingsStore.persistentReminder.first(),
                hasShownBatteryHint = settingsStore.hasShownBatteryHint.first(),
                selectedPhase = settingsStore.selectedPhase.first(),
                lastLaunchDate = settingsStore.lastLaunchDate.first()
            )
        }
        settingsStore.focusMinutes.onEach { v ->
            _settingsSnapshot.value = _settingsSnapshot.value.copy(focusMinutes = v)
        }.launchIn(appScope)
        settingsStore.shortBreakMinutes.onEach { v ->
            _settingsSnapshot.value = _settingsSnapshot.value.copy(shortBreakMinutes = v)
        }.launchIn(appScope)
        settingsStore.longBreakMinutes.onEach { v ->
            _settingsSnapshot.value = _settingsSnapshot.value.copy(longBreakMinutes = v)
        }.launchIn(appScope)
        settingsStore.vibrationStrength.onEach { v ->
            _settingsSnapshot.value = _settingsSnapshot.value.copy(vibrationStrength = v)
        }.launchIn(appScope)
        settingsStore.persistentReminder.onEach { v ->
            _settingsSnapshot.value = _settingsSnapshot.value.copy(persistentReminder = v)
        }.launchIn(appScope)
        settingsStore.hasShownBatteryHint.onEach { h ->
            _settingsSnapshot.value = _settingsSnapshot.value.copy(hasShownBatteryHint = h)
        }.launchIn(appScope)
        settingsStore.selectedPhase.onEach { phase ->
            _settingsSnapshot.value = _settingsSnapshot.value.copy(selectedPhase = phase)
        }.launchIn(appScope)
        settingsStore.lastLaunchDate.onEach { date ->
            _settingsSnapshot.value = _settingsSnapshot.value.copy(lastLaunchDate = date)
        }.launchIn(appScope)
    }

    /**
     * 全局 effect handler——所有 [TimerEffect] 的最终执行点。
     *
     * 分发规则：
     * - [TimerEffect.StartForegroundService] / [TimerEffect.StopForegroundService]
     *   → 启动 / 停止 [com.pomotick.service.TimerForegroundService]
     * - [TimerEffect.StartReminder] / [TimerEffect.StopReminder] / [TimerEffect.UpdateNotification]
     *   → 路由到 [currentService]（若有）。这些 effect 既可由 Service 的 tick 触发，
     *   也可由 UI 直接调用 `repo.handleEvent(...)`（如用户在 RINGING 页点"知道了/开始休息"）触发；
     *   统一在此处路由避免丢失。
     * - [TimerEffect.SaveRuntime] / [TimerEffect.ClearRuntime] / [TimerEffect.RecordSession]
     *   → **不应**到达此处（由 [TimerRepository] 直接执行）
     */
    private suspend fun handleGlobalEffect(effect: TimerEffect) {
        when (effect) {
            is TimerEffect.StartForegroundService -> {
                com.pomotick.service.TimerForegroundService.start(this)
            }
            is TimerEffect.StopForegroundService -> {
                com.pomotick.service.TimerForegroundService.stop(this)
            }
            is TimerEffect.StartReminder,
            is TimerEffect.StopReminder,
            is TimerEffect.UpdateNotification -> {
                currentService?.handleEffect(effect)
            }
            is TimerEffect.SaveRuntime,
            is TimerEffect.ClearRuntime,
            is TimerEffect.SaveSelectedPhase,
            is TimerEffect.RecordSession -> {
                // 兜底：这些 effect 由 TimerRepository 直接执行，不应到达此处。
                // 若到达，说明 Repository 漏处理了——打日志便于排查。
                android.util.Log.w(
                    "PomoTickApp",
                    "handleGlobalEffect received data effect (should have been handled by Repository): $effect"
                )
            }
        }
    }

    fun settingsSnapshot(): SettingsSnapshot = _settingsSnapshot.value

    /**
     * 当前活跃 Service 是否在运行。用于调试 / 日志。
     */
    fun isServiceRunning(): Boolean = currentService != null

    companion object {
        fun get(context: Context): PomoTickApp =
            context.applicationContext as PomoTickApp

        /**
         * 当前活跃运行时状态为 RINGING（用于 MainActivity 决定是否打开提醒页）。
         */
        fun shouldShowReminderScreen(context: Context): Boolean {
            val app = get(context)
            // 同步从 DataStore 读取最新值
            val runtime = kotlinx.coroutines.runBlocking {
                app.runtimeStore.current()
            }
            return runtime?.runState == TimerRunState.RINGING
        }
    }
}
