package com.pomotick.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pomotick.PomoTickApp
import com.pomotick.data.SettingsSnapshot
import com.pomotick.data.TimerRepository
import com.pomotick.data.TimerSession
import com.pomotick.timer.ResponseAction
import com.pomotick.timer.TimerEvent
import com.pomotick.timer.TimerPhase
import com.pomotick.timer.TimerRunState
import com.pomotick.timer.TimerRuntimeState
import com.pomotick.timer.actualFocusMillis
import com.pomotick.timer.remainingMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine as flowCombine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * UI 状态聚合。
 */
data class TimerUiState(
    val runtime: TimerRuntimeState?,
    val remainingMs: Long,
    val phase: TimerPhase?,
    val runState: TimerRunState,
    val settings: SettingsSnapshot,
    val todayCount: Int = 0,
    val todayFocusMillis: Long = 0L,
    val latestCompleted: TimerSession? = null
) {
    companion object {
        val IDLE = TimerUiState(
            runtime = null,
            remainingMs = 0L,
            phase = null,
            runState = TimerRunState.IDLE,
            settings = SettingsSnapshot.DEFAULT
        )
    }
}

/**
 * 主 ViewModel。
 *
 * - 聚合 Repository + SettingsStore + TimerEngine
 * - 把用户事件送入 Engine，并把 effects 交由 Repository 解释执行
 * - UI 可见时启动每秒 tick 协程，仅刷新"显示用 remainingMs"（不影响真实计时）
 */
class TimerViewModel(
    private val repo: TimerRepository,
    private val app: Application
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(TimerUiState.IDLE)
    val state: StateFlow<TimerUiState> = _state.asStateFlow()

    init {
        observeRuntime()
        observeSettings()
        startUiTick()
    }

    private fun observeRuntime() {
        viewModelScope.launch {
            repo.currentRuntime.collect { runtime ->
                val now = System.currentTimeMillis()
                _state.update { current ->
                    current.copy(
                        runtime = runtime,
                        runState = runtime?.runState ?: TimerRunState.IDLE,
                        phase = runtime?.phase,
                        remainingMs = runtime?.let { remainingMillis(now, it) } ?: 0L
                    )
                }
            }
        }
    }

    /**
     * **启动恢复**——在 APP 启动 / ViewModel 创建后调用一次。
     *
     * 职责：
     * 1. 等待 DataStore 加载 runtime 状态
     * 2. 若 [TimerRuntimeState.runState] == RUNNING 且 `now >= targetEnd`，
     *    主动提交一次 [TimerEvent.OnTick] → Engine 自动转 RINGING 并触发 StartReminder
     * 3. 若 RUNNING 且尚未到点 → **重启 ForegroundService** 接管 tick
     * 4. 若 PAUSED → 重启 Service（tick 继续冻结，但 Service 准备好）
     * 5. 若 RINGING → 重启 Service + 不重复 StartReminder（避免重置提醒节奏）
     *
     * 这覆盖了 Service 被杀 / APP 闪退 / 重启手表 等恢复场景。
     */
    fun onAppStart() {
        viewModelScope.launch {
            val initial = repo.currentRuntime.first()
            if (initial == null) return@launch

            when (initial.runState) {
                TimerRunState.RUNNING -> {
                    val now = System.currentTimeMillis()
                    if (now >= initial.targetEndAtEpochMillis) {
                        // 已到点未触发 → 主动 tick 触发 RINGING + 提醒
                        // Engine 会同时发出 SaveRuntime + StartReminder，由 PomoTickApp.handleGlobalEffect
                        // 自动启动 ForegroundService
                        repo.handleEvent(TimerEvent.OnTick(now))
                    } else {
                        // RUNNING 且未到点 → 必须重启 Service，否则 25min 后没人触发 OnTick/提醒
                        com.pomotick.service.TimerForegroundService.start(app)
                    }
                }
                TimerRunState.PAUSED -> {
                    // 暂停态：时间已冻结在 pausedAt；重启 Service 让它准备好接手恢复事件
                    com.pomotick.service.TimerForegroundService.start(app)
                }
                TimerRunState.RINGING -> {
                    // 已 RINGING → 重启 Service 恢复震动提醒；不重复 StartReminder（避免重置节奏）
                    com.pomotick.service.TimerForegroundService.start(app)
                }
                TimerRunState.IDLE, TimerRunState.FINISHED -> Unit
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            val pomotickApp = app as PomoTickApp
            flowCombine(
                pomotickApp.settingsStore.focusMinutes,
                pomotickApp.settingsStore.shortBreakMinutes,
                pomotickApp.settingsStore.longBreakMinutes,
                pomotickApp.settingsStore.vibrationStrength,
                pomotickApp.settingsStore.persistentReminder
            ) { focus, short, long, strength, persistent ->
                SettingsSnapshot(
                    focusMinutes = focus,
                    shortBreakMinutes = short,
                    longBreakMinutes = long,
                    vibrationStrength = strength,
                    persistentReminder = persistent
                )
            }.collect { snap ->
                _state.update { it.copy(settings = snap) }
            }
        }
    }

    /**
     * 每秒刷新"显示用 remainingMs"——不影响真实计时（Engine 仍用 timestamp 计算）。
     */
    private fun startUiTick() {
        viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                val now = System.currentTimeMillis()
                val runtime = _state.value.runtime
                _state.update {
                    it.copy(
                        remainingMs = runtime?.let { r -> remainingMillis(now, r) } ?: 0L
                    )
                }
            }
        }
    }

    // ===== 用户事件 =====

    fun onStartFocus() {
        viewModelScope.launch {
            val plannedMs = _state.value.settings.focusMinutes * 60_000L
            repo.handleEvent(TimerEvent.Start(System.currentTimeMillis(), TimerPhase.FOCUS, plannedMs))
        }
    }

    fun onPause() {
        viewModelScope.launch {
            repo.handleEvent(TimerEvent.Pause(System.currentTimeMillis()))
        }
    }

    fun onResume() {
        viewModelScope.launch {
            repo.handleEvent(TimerEvent.Resume(System.currentTimeMillis()))
        }
    }

    fun onExtend5Min() {
        viewModelScope.launch {
            repo.handleEvent(TimerEvent.Extend(System.currentTimeMillis(), 5L * 60L * 1000L))
        }
    }

    fun onFinishEarly() {
        viewModelScope.launch {
            repo.handleEvent(TimerEvent.FinishEarly(System.currentTimeMillis()))
        }
    }

    fun onAbandon() {
        viewModelScope.launch {
            repo.handleEvent(TimerEvent.Abandon(System.currentTimeMillis()))
        }
    }

    fun onRespond(action: ResponseAction) {
        viewModelScope.launch {
            val options = if (action == ResponseAction.StartBreak) {
                computeBreakOptions()
            } else {
                com.pomotick.timer.RespondOptions()
            }
            repo.handleEvent(
                TimerEvent.Respond(System.currentTimeMillis(), action, options)
            )
        }
    }

    /**
     * 启动休息（兼容旧 API，等价 onRespond(StartBreak)）。
     */
    fun onStartBreak() = onRespond(ResponseAction.StartBreak)

    /**
     * 计算休息阶段的 options：
     * - 读取最近 [recentForLongBreak] 条 FOCUS COMPLETED session
     * - 若达到 [longBreakEvery] 个 → LONG_BREAK
     * - 否则 → SHORT_BREAK
     * - plannedMs 取自 settings.shortBreakMinutes / longBreakMinutes
     */
    private suspend fun computeBreakOptions(): com.pomotick.timer.RespondOptions {
        val snap = _state.value.settings
        val history = repo.recentCompletedFocus(limit = LONG_BREAK_EVERY)
        val nextPhase = com.pomotick.timer.TimerEngine.nextPhase(history)
        val plannedMs = when (nextPhase) {
            com.pomotick.timer.TimerPhase.LONG_BREAK -> snap.longBreakMinutes * 60_000L
            else -> snap.shortBreakMinutes * 60_000L
        }
        return com.pomotick.timer.RespondOptions(
            nextPhase = nextPhase,
            plannedMs = plannedMs
        )
    }

    // ===== 统计 =====

    fun refreshTodayStats() {
        viewModelScope.launch {
            val dayStart = startOfTodayMillis()
            _state.update {
                it.copy(
                    todayCount = repo.countCompletedFocusSince(dayStart),
                    todayFocusMillis = repo.sumFocusMillisSince(dayStart),
                    latestCompleted = repo.latestCompletedFocus()
                )
            }
        }
    }

    private fun startOfTodayMillis(): Long {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    companion object {
        /** 每完成 N 个 FOCUS 后切换到 LONG_BREAK */
        const val LONG_BREAK_EVERY = 4
    }
}

/**
 * 手动注入 ViewModel（无 Hilt）。
 */
class TimerViewModelFactory(
    private val app: Application,
    private val repo: TimerRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TimerViewModel::class.java)) {
            "Unknown ViewModel: ${modelClass.name}"
        }
        return TimerViewModel(repo, app) as T
    }
}
