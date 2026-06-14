package com.pomotick.timer

import com.pomotick.data.SessionStatus
import com.pomotick.data.TimerSession

/**
 * [TimerEngine] 产出的副作用意图。
 *
 * **Engine 自身不执行任何 IO**——仅返回这些意图，由调用方（ViewModel / Repository /
 * Service）解释执行。执行顺序由调用方控制。
 */
sealed class TimerEffect {

    /** 持久化 [TimerRuntimeState] 到 DataStore */
    data class SaveRuntime(val state: TimerRuntimeState) : TimerEffect()

    /** 清空 runtime state */
    object ClearRuntime : TimerEffect()

    /** Persist the phase shown on the idle start screen. */
    data class SaveSelectedPhase(val phase: TimerPhase) : TimerEffect()

    /**
     * 写入一条历史 [TimerSession]。
     */
    data class RecordSession(val session: TimerSession) : TimerEffect()

    /** 启动 [com.pomotick.service.TimerForegroundService] */
    object StartForegroundService : TimerEffect()

    /** 停止 [com.pomotick.service.TimerForegroundService] */
    object StopForegroundService : TimerEffect()

    /** 启动震动提醒（[com.pomotick.reminder.ReminderManager]） */
    data class StartReminder(val phase: TimerPhase, val strength: Int) : TimerEffect()

    /** 立即停止震动 */
    object StopReminder : TimerEffect()

    /** 更新常驻通知（剩余时间 + 阶段） */
    data class UpdateNotification(val remainingMs: Long, val phase: TimerPhase) : TimerEffect()

    companion object {
        /**
         * 构造 [TimerSession] 的便捷工厂。
         */
        fun buildSession(
            phase: TimerPhase,
            startedAtEpochMillis: Long,
            endedAtEpochMillis: Long?,
            plannedDurationMillis: Long,
            actualFocusMillis: Long,
            status: SessionStatus,
            extensionCount: Int
        ): TimerSession = TimerSession(
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
 * Engine 处理结果。
 */
data class TimerEngineResult(
    /**
     * 新 runtime state。null 表示回到 IDLE。
     */
    val newState: TimerRuntimeState?,
    val effects: List<TimerEffect>
) {
    companion object {
        fun idle(effects: List<TimerEffect> = emptyList()) = TimerEngineResult(null, effects)

        fun of(state: TimerRuntimeState, effects: List<TimerEffect>) =
            TimerEngineResult(state, effects)
    }
}
