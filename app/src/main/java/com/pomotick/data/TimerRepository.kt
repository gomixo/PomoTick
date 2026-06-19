package com.pomotick.data

import android.util.Log
import com.pomotick.alarm.TimerAlarmScheduler
import com.pomotick.timer.TimerEffect
import com.pomotick.timer.TimerEngineResult
import com.pomotick.timer.TimerEvent
import com.pomotick.timer.TimerPhase
import com.pomotick.timer.TimerRunState
import com.pomotick.timer.TimerRuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
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
 *
 * v0.2.1: 构造器新增 [alarmScheduler]，并在 `handleEvent` 末尾根据 `newState.runState`
 * 调 [syncAlarm] 注册 / 取消 AlarmManager 精确闹钟。
 */
class TimerRepository(
    private val dao: TimerSessionDao,
    private val runtime: RuntimeStateStore,
    private val settings: SettingsStore,
    private val externalScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val effectHandler: suspend (TimerEffect) -> Unit,
    /**
     * v0.2.1: AlarmManager 封装，用于在 [handleEvent] 末尾注册 / 取消精确闹钟。
     * 仅在 RUNNING 状态下 schedule(targetEnd)，其他状态一律 cancel。
     */
    private val alarmScheduler: TimerAlarmScheduler
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
     * v0.2.1: 冷启动同步恢复 runtime。
     *
     * 由 [com.pomotick.PomoTickApp.onCreate] 在主线程上用 `runBlocking { ... }` 调用，
     * 写入 [com.pomotick.alarm.TimerAlarmReceiver] 触发时 `currentRuntime.value` 已有正确值，
     * 消除 cold start race。
     *
     * 注意：这里**不**调 [syncAlarm]——alarmscheduler 由 PomoTickApp 在 bootstrap 完成后
     * 调 [com.pomotick.PomoTickApp.reregisterAlarmFromRuntime] 显式重建。
     */
    fun bootstrap(runtimeState: TimerRuntimeState?) {
        _currentRuntime.value = runtimeState
    }

    /**
     * 处理事件：先调 [com.pomotick.timer.TimerEngine.process] 算出结果，
     * 再串行执行所有 effects，最后更新内存缓存与 AlarmManager。
     *
     * v0.2.1: 在末尾调 [syncAlarm] 注册 / 取消精确闹钟——RUNNING 时 schedule(targetEnd)，
     * 其他状态（PAUSED / RINGING / IDLE / FINISHED / null）一律 cancel。
     */
    suspend fun handleEvent(event: TimerEvent): TimerEngineResult = mutex.withLock {
        val now = clock()
        val current = _currentRuntime.value
        val result = com.pomotick.timer.TimerEngine.process(event, current, now)
        executeEffects(result.effects)
        _currentRuntime.value = result.newState
        syncAlarm(result.newState)  // v0.2.1 新增
        result
    }

    /**
     * v0.2.1: 把当前 runtime 同步到 AlarmManager。
     *
     * - `null` / `IDLE` / `FINISHED` / `PAUSED` / `RINGING` → cancel
     * - `RUNNING` → schedule(targetEndAtEpochMillis)
     *
     * 任何异常都不抛出——alarm 失败只影响"息屏到点唤醒可靠性"，不影响 Engine / 业务逻辑。
     */
    private fun syncAlarm(state: TimerRuntimeState?) {
        try {
            when {
                state == null -> alarmScheduler.cancel()
                state.runState == TimerRunState.RUNNING ->
                    alarmScheduler.schedule(state.targetEndAtEpochMillis)
                state.runState == TimerRunState.PAUSED -> alarmScheduler.cancel()
                state.runState == TimerRunState.RINGING -> alarmScheduler.cancel()
                state.runState == TimerRunState.IDLE -> alarmScheduler.cancel()
                state.runState == TimerRunState.FINISHED -> alarmScheduler.cancel()
            }
        } catch (e: Exception) {
            // alarm 调度失败不影响业务；仅记日志
            Log.w(TAG, "syncAlarm failed (non-fatal): ${e.message}")
        }
    }

    /**
     * 应用一批 effects（不更新 _currentRuntime，由调用方自行处理）。
     *
     * v0.2 P1 修复：
     * - 新增 [TimerEffect.AdvanceCycle] 处理——调用 `settings.setCyclePosition(...)`，
     *   把轮次位置维护从 ViewModel 下放到 Engine 出口
     */
    private suspend fun executeEffects(effects: List<TimerEffect>) {
        for (effect in effects) {
            try {
                when (effect) {
                    is TimerEffect.SaveRuntime -> runtime.save(effect.state)
                    is TimerEffect.ClearRuntime -> runtime.clear()
                    is TimerEffect.SaveSelectedPhase -> settings.setSelectedPhase(effect.phase)
                    is TimerEffect.RecordSession -> dao.insert(effect.session)
                    is TimerEffect.AdvanceCycle -> advanceCycleFor(effect.completedPhase)
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
     * v0.2 P1：完成阶段后推进轮次位置。
     *
     * - [TimerPhase.FOCUS] 完成 → `pos = (pos + 1).coerceAtMost(cycles)`
     * - [TimerPhase.LONG_BREAK] 完成 → `pos = 0`
     * - [TimerPhase.SHORT_BREAK] 完成 → 不变
     *
     * 由 [com.pomotick.timer.TimerEngine] 写出 [TimerEffect.AdvanceCycle] 时调用，
     * 与 UI 按钮 / 通知 Action 走**完全相同**的入口——避免轮次错乱。
     */
    private suspend fun advanceCycleFor(completedPhase: TimerPhase) {
        when (completedPhase) {
            TimerPhase.FOCUS -> {
                val pos = settings.cyclePosition.firstOrNull() ?: 0
                val cycles = settings.focusCyclesBeforeLongBreak.firstOrNull() ?: 3
                settings.setCyclePosition((pos + 1).coerceAtMost(cycles))
            }
            TimerPhase.LONG_BREAK -> settings.setCyclePosition(0)
            TimerPhase.SHORT_BREAK -> Unit
        }
    }

    /**
     * 读取今日统计。
     */
    suspend fun countCompletedFocusSince(dayStart: Long): Int =
        dao.countCompletedFocusSince(dayStart)

    suspend fun sumFocusMillisSince(dayStart: Long): Long =
        dao.sumFocusMillisSince(dayStart)

    /**
     * v0.2 §8: 区间 [start, end) 内 FOCUS 累计毫秒。
     */
    suspend fun sumFocusMillisBetween(start: Long, end: Long): Long =
        dao.sumFocusMillisBetween(start, end)

    /**
     * v0.2 §8: 区间 [start, end) 内休息（SHORT + LONG）累计毫秒。
     */
    suspend fun sumBreakMillisBetween(start: Long, end: Long): Long =
        dao.sumBreakMillisBetween(start, end)

    suspend fun latestCompletedFocus(): TimerSession? =
        dao.latestCompletedFocus()

    /**
     * v0.2 第五轮 P0 性能修复：把"最近 7 天每日专注毫秒"从 7 次 sequential 查询
     * 合并为 1 条 `GROUP BY day` SQL。
     *
     * @param todayStart   今日 00:00 epoch millis
     * @param dayMillis    一天的毫秒数（24h）
     * @param weekStart    周聚合起点（todayStart - 6 * dayMillis）
     * @param dayAfterEnd  周聚合结束（半开区间，不含）
     * @return `[(dayOffset, focusMillis), ...]`，dayOffset 范围 [-6, 0]
     */
    suspend fun sumFocusMillisGroupedByDay(
        todayStart: Long,
        dayMillis: Long,
        weekStart: Long,
        dayAfterEnd: Long
    ): List<DailyFocusAggregate> =
        dao.sumFocusMillisGroupedByDay(todayStart, dayMillis, weekStart, dayAfterEnd)

    suspend fun recentCompletedFocus(limit: Int): List<TimerSession> =
        dao.recentCompletedFocus(limit)

    fun observeLatestCompletedFocus() = dao.observeLatestCompletedFocus()

    companion object {
        private const val TAG = "PomoTick/Repo"
    }
}
