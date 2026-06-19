package com.pomotick.timer

import com.pomotick.data.SessionStatus
import com.pomotick.data.TimerSession

/**
 * 副作用（sealed），由 [TimerEngine] 发出、由 [com.pomotick.data.TimerRepository] / [com.pomotick.PomoTickApp] / [com.pomotick.service.TimerForegroundService] 各自执行。
 *
 * v0.2 关键变化：
 * - `StartReminder` 携带 `durationMs`（30s 首次 / 15s 重复）——Service 不再反查 runtime
 * - 新增 `AdvanceCycle(phase)` —— Engine 写出 session 时同步发出，由 Repository 翻译为
 *   `settingsStore.setCyclePosition(...)`；统一"完成 → 推进轮次"入口
 */
sealed class TimerEffect {
    /** 持久化当前 runtime 状态。 */
    data class SaveRuntime(val state: TimerRuntimeState) : TimerEffect()

    /** 写入一条 session 记录（专注/休息段）。 */
    data class RecordSession(val session: TimerSession) : TimerEffect()

    /** 启动震动 + 铃声（持续 `durationMs`）。 */
    data class StartReminder(
        val phase: TimerPhase,
        /** 提醒持续时长。Engine 根据是否重复（`state.repeatReminderFired`）决定 30s 或 15s。 */
        val durationMs: Long
    ) : TimerEffect()

    /** 停止当前震动 + 铃声（不清 runtime、不停服务）。 */
    object StopReminder : TimerEffect()

    /** 启动前台 Service（持有 1001 通知）。 */
    object StartForegroundService : TimerEffect()

    /** 停止前台 Service。 */
    object StopForegroundService : TimerEffect()

    /** 刷新通知（remaining 毫秒、当前 phase）。 */
    data class UpdateNotification(
        val remainingMs: Long,
        val phase: TimerPhase
    ) : TimerEffect()

    /** 清除 runtime 状态（用户确认完成、停止、重置）。 */
    object ClearRuntime : TimerEffect()

    /** 更新设置：当前选中阶段（影响 IDLE 时显示）。 */
    data class SaveSelectedPhase(val phase: TimerPhase) : TimerEffect()

    /**
     * v0.2 P1：完成阶段后，Engine 通知 Repository 推进轮次。
     *
     * 由 [com.pomotick.data.TimerRepository] 翻译为 `settings.setCyclePosition(...)`：
     * - [TimerPhase.FOCUS] 完成 → `pos = (pos + 1).coerceAtMost(cycles)`
     * - [TimerPhase.LONG_BREAK] 完成 → `pos = 0`
     * - [TimerPhase.SHORT_BREAK] / 其他 → 不变
     *
     * 这么做的好处：通知 Action（绕开 ViewModel）和 UI 按钮走**完全相同**的
     * 决策路径，轮次位置不会因为入口不同而错乱。
     */
    data class AdvanceCycle(val completedPhase: TimerPhase) : TimerEffect()

    companion object {
        /**
         * 构造一条 session 记录的工厂。
         */
        fun buildSession(
            phase: TimerPhase,
            startedAtEpochMillis: Long,
            endedAtEpochMillis: Long,
            plannedDurationMillis: Long,
            actualFocusMillis: Long,
            status: SessionStatus,
            extensionCount: Int
        ): TimerSession = TimerSession(
            id = 0L,
            phase = phase,
            startedAtEpochMillis = startedAtEpochMillis,
            endedAtEpochMillis = endedAtEpochMillis,
            plannedDurationMillis = plannedDurationMillis,
            actualFocusMillis = actualFocusMillis,
            status = status,
            extensionCount = extensionCount
        )
    }
}

/**
 * 引擎处理结果。
 */
data class TimerEngineResult(
    val newState: TimerRuntimeState?,
    val effects: List<TimerEffect>
) {
    companion object {
        fun of(state: TimerRuntimeState, effects: List<TimerEffect>) = TimerEngineResult(state, effects)
        fun idle(effects: List<TimerEffect> = emptyList()) = TimerEngineResult(null, effects)
    }
}
