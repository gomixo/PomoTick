package com.pomotick.timer

/**
 * 活跃 timer 的运行时状态（**唯一真实计时来源**）。
 *
 * 设计要点：
 * - **不依赖每秒后台循环**——任何时刻的剩余时间 = `targetEndAtEpochMillis - now`（考虑 PAUSED）
 * - `pausedAtEpochMillis == null` 表示"未暂停"，避免 `0L` 与真实暂停时间混淆
 * - 暂停后恢复只顺延 [targetEndAtEpochMillis]，保证"实际专注时长 = 实际 RUNNING 时长"
 * - [sessionCompletionRecorded] 用于避免 [TimerEngine] 在多个事件路径中重复写同一 session
 */
data class TimerRuntimeState(
    val sessionId: Long,
    val phase: TimerPhase,
    val runState: TimerRunState,
    val plannedDurationMillis: Long,
    val startedAtEpochMillis: Long,
    val targetEndAtEpochMillis: Long,
    val pausedAtEpochMillis: Long?,
    val accumulatedPausedMillis: Long,
    val extensionCount: Int = 0,
    /**
     * 当前 session 是否已写入历史表。
     *
     * 用途：
     * - [TimerEvent.FinishEarly] 立即写入并置 true → 后续 Respond.KnowIt / StartBreak 不再重复写
     * - 自然到点（RINGING）保持 false → Respond.KnowIt / StartBreak 才会写一次
     */
    val sessionCompletionRecorded: Boolean = false
) {
    init {
        require(plannedDurationMillis > 0L) { "plannedDurationMillis must be > 0" }
        require(targetEndAtEpochMillis >= startedAtEpochMillis) {
            "targetEndAtEpochMillis must be >= startedAtEpochMillis"
        }
        require(accumulatedPausedMillis >= 0L) { "accumulatedPausedMillis must be >= 0" }
        require(extensionCount >= 0) { "extensionCount must be >= 0" }
    }

    val isPaused: Boolean get() = runState == TimerRunState.PAUSED && pausedAtEpochMillis != null

    val isRunning: Boolean get() = runState == TimerRunState.RUNNING
}

/**
 * 计算剩余毫秒（pure）。
 *
 * - RUNNING / RINGING / FINISHED：`targetEnd - now`
 * - PAUSED：`targetEnd - pausedAt`（时间冻结）
 * - IDLE：调用方不应传入此状态
 */
fun remainingMillis(now: Long, state: TimerRuntimeState): Long {
    val anchor = if (state.runState == TimerRunState.PAUSED) {
        state.pausedAtEpochMillis ?: now
    } else {
        now
    }
    return (state.targetEndAtEpochMillis - anchor).coerceAtLeast(0L)
}

/**
 * 计算"已实际专注毫秒"（不含 PAUSED 时长、**不超过 plannedDurationMillis + 延长**）。
 *
 * - PAUSED：冻结在 `pausedAtEpochMillis`（不随 `now` 增长）
 * - RUNNING：`min(now, targetEndAtEpochMillis) - startedAt - accumulatedPaused`
 *            （封顶于 targetEnd，延长时 targetEnd 已上移 → 实际时长包含延长部分）
 * - RINGING / FINISHED：同样封顶在 targetEnd（用户晚响应不会把等待时间算进专注）
 */
fun actualFocusMillis(now: Long, state: TimerRuntimeState): Long {
    val effectiveNow = when (state.runState) {
        TimerRunState.PAUSED -> state.pausedAtEpochMillis ?: now
        TimerRunState.RUNNING,
        TimerRunState.RINGING,
        TimerRunState.FINISHED -> minOf(now, state.targetEndAtEpochMillis)
        TimerRunState.IDLE -> state.startedAtEpochMillis  // 不应到达
    }
    val rawElapsed = effectiveNow - state.startedAtEpochMillis
    // PAUSED 时不累积"now - pausedAt"那段（已冻结在 pausedAtEpochMillis）
    return (rawElapsed - state.accumulatedPausedMillis).coerceAtLeast(0L)
}
