package com.pomotick.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pomotick.timer.TimerPhase

/**
 * 已完成的番茄 session 记录。
 * 用于"今日统计"和未来扩展的周统计。
 *
 * 注意：当前运行时 timer 状态由 [com.pomotick.data.RuntimeStateStore] 管理，
 * 不与历史记录混在一起。
 */
@Entity(tableName = "timer_sessions")
data class TimerSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val phase: TimerPhase,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    val plannedDurationMillis: Long,
    val actualFocusMillis: Long,
    val status: SessionStatus,
    val extensionCount: Int = 0
) {
    /**
     * session 持续时长（毫秒）。
     */
    val durationMillis: Long
        get() = (endedAtEpochMillis ?: startedAtEpochMillis) - startedAtEpochMillis
}

/**
 * session 结束状态。
 */
enum class SessionStatus {
    /** 正常完成（含到点自然完成 + 用户提前结束） */
    COMPLETED,

    /** 用户放弃 */
    INTERRUPTED,

    /** 跳过休息 */
    SKIPPED
}
