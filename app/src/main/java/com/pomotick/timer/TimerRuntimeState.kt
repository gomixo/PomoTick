package com.pomotick.timer

/**
 * 运行时状态——由 [TimerEngine] 维护，由 [com.pomotick.data.RuntimeStateStore] 持久化。
 *
 * ## v0.2 关键变化
 *
 * - **不存"剩余秒数"**——只存时间戳，恢复时一律 `targetEndAtEpochMillis - now`
 *   重启 / 杀掉 App 后不漂移、不丢秒数
 * - **5 个配置快照**（`focusMinutesAtStart` / `shortBreakMinutesAtStart` /
 *   `longBreakMinutesAtStart` / `cyclesBeforeLongBreakAtStart` / `cyclePositionAtStart`）
 *   —— Engine 在不依赖 SettingsStore 的前提下能计算"下一阶段 + plannedMs"
 * - **3 个 §4 字段**（`ringingStartedAtEpochMillis` / `awaitingRepeatSinceEpochMillis` /
 *   `repeatReminderFired`）—— RINGING 状态下的重复提醒调度
 *
 * App 重启后从 [com.pomotick.data.RuntimeStateStore] 加载，整个字段集都能
 * 用于"以时间戳为恢复依据"——Engine 的所有决策都是纯函数。
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
    val extensionCount: Int,
    val sessionCompletionRecorded: Boolean,
    // ===== §4 重复提醒调度字段 =====
    /**
     * 当前 RINGING 状态进入时间（epoch millis）。
     * 由 [TimerEngine.enterRinging] 在 RUNNING→RINGING 时设置。
     */
    val ringingStartedAtEpochMillis: Long? = null,
    /**
     * 当前 RINGING 状态进入"等待 3 分钟重复"时刻。
     * OnTick 在 `now - ringingStartedAtEpochMillis >= 30s` 时设置。
     */
    val awaitingRepeatSinceEpochMillis: Long? = null,
    /**
     * 重复提醒是否已触发（true 后不再触发）。
     * OnTick 在 `now - awaitingRepeatSinceEpochMillis >= 3min` 时设为 true。
     */
    val repeatReminderFired: Boolean = false,
    // ===== §9 配置快照字段 =====
    val cyclePositionAtStart: Int = 0,
    val longBreakMinutesAtStart: Int = 15,
    val shortBreakMinutesAtStart: Int = 5,
    val focusMinutesAtStart: Int = 25,
    val cyclesBeforeLongBreakAtStart: Int = 3
) {
    init {
        require(plannedDurationMillis > 0L) { "plannedDurationMillis must be > 0" }
        require(targetEndAtEpochMillis >= startedAtEpochMillis) {
            "targetEndAtEpochMillis must be >= startedAtEpochMillis"
        }
        require(accumulatedPausedMillis >= 0L) { "accumulatedPausedMillis must be >= 0" }
        require(extensionCount >= 0) { "extensionCount must be >= 0" }
        require(cyclePositionAtStart >= 0) { "cyclePositionAtStart must be >= 0" }
        require(longBreakMinutesAtStart in 1..120) { "longBreakMinutesAtStart must be 1..120" }
        require(shortBreakMinutesAtStart in 1..60) { "shortBreakMinutesAtStart must be 1..60" }
        require(focusMinutesAtStart in 5..45) { "focusMinutesAtStart must be 5..45" }
        require(cyclesBeforeLongBreakAtStart in 2..6) {
            "cyclesBeforeLongBreakAtStart must be 2..6"
        }
    }
}
