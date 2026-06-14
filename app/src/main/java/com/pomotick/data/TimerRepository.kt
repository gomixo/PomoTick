package com.pomotick.data

import android.util.Log
import com.pomotick.timer.TimerEffect
import com.pomotick.timer.TimerEngineResult
import com.pomotick.timer.TimerEvent
import com.pomotick.timer.TimerRuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **唯一 IO 入口** + [com.pomotick.timer.TimerEngine] 副作用的解释执行者。
 *
 * 责任：
 * 1. 包装 DAO / DataStore / Settings，提供读写 API。
 * 2. 执行 [TimerEffect]——按顺序调用底层 IO、Service、ReminderManager 等。
 * 3. 缓存"当前 runtime state"的内存视图，供 [com.pomotick.ui.TimerViewModel] 实时观察。
 *
 * **不直接做状态决策**——状态机逻辑在 [com.pomotick.timer.TimerEngine] 中。
 */
class TimerRepository(
    private val dao: TimerSessionDao,
    private val runtime: RuntimeStateStore,
    private val settings: SettingsStore,
    private val externalScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val effectHandler: suspend (TimerEffect) -> Unit
) {

    private val mutex = Mutex()

    private val _currentRuntime = MutableStateFlow<TimerRuntimeState?>(null)
    val currentRuntime: StateFlow<TimerRuntimeState?> = _currentRuntime.asStateFlow()

    init {
        // 启动时从 DataStore 读取 runtime 状态到内存缓存
        runtime.flow
            .onEach { state -> _currentRuntime.value = state }
            .launchIn(externalScope)
    }

    /**
     * 处理事件：先调 [com.pomotick.timer.TimerEngine.process] 算出结果，
     * 再串行执行所有 effects，最后更新内存缓存。
     */
    suspend fun handleEvent(event: TimerEvent): TimerEngineResult = mutex.withLock {
        val now = clock()
        val current = _currentRuntime.value
        val result = com.pomotick.timer.TimerEngine.process(event, current, now)
        executeEffects(result.effects)
        _currentRuntime.value = result.newState
        result
    }

    /**
     * 应用一批 effects（不更新 _currentRuntime，由调用方自行处理）。
     */
    private suspend fun executeEffects(effects: List<TimerEffect>) {
        for (effect in effects) {
            try {
                when (effect) {
                    is TimerEffect.SaveRuntime -> runtime.save(effect.state)
                    is TimerEffect.ClearRuntime -> runtime.clear()
                    is TimerEffect.SaveSelectedPhase -> settings.setSelectedPhase(effect.phase)
                    is TimerEffect.RecordSession -> dao.insert(effect.session)
                    is TimerEffect.StartForegroundService,
                    is TimerEffect.StopForegroundService,
                    is TimerEffect.StartReminder,
                    is TimerEffect.StopReminder,
                    is TimerEffect.UpdateNotification -> effectHandler(effect)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute effect: $effect", e)
            }
        }
    }

    /**
     * 读取今日统计。
     */
    suspend fun countCompletedFocusSince(dayStart: Long): Int =
        dao.countCompletedFocusSince(dayStart)

    suspend fun sumFocusMillisSince(dayStart: Long): Long =
        dao.sumFocusMillisSince(dayStart)

    suspend fun latestCompletedFocus(): TimerSession? =
        dao.latestCompletedFocus()

    suspend fun recentCompletedFocus(limit: Int): List<TimerSession> =
        dao.recentCompletedFocus(limit)

    fun observeLatestCompletedFocus() = dao.observeLatestCompletedFocus()

    companion object {
        private const val TAG = "PomoTick/Repo"
    }
}
