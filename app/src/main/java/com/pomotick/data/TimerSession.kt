package com.pomotick.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pomotick.timer.TimerPhase

/**
 * 已完成的番茄 session 记录。
 * 用于"今日统计"和未来扩展的周统计。
 *
 * 注意：当前运行时 timer 状态由 [com.pomotick.data.RuntimeStateStore] 管理，
 * 不与历史记录混在一起。
 *
 * v0.2 第五轮 P0 性能修复：增加 `status_phase_ended` 组合索引，覆盖
 * `WHERE status IN (...) AND phase IN (...) AND endedAtEpochMillis IS NOT NULL
 *  AND endedAtEpochMillis >= ? AND endedAtEpochMillis < ?` 的所有 DAO 查询。
 *
 * 历史数据超过 1000 条后，无索引的 `SELECT SUM(actualFocusMillis)` 走全表扫描，
 * 手表 IO 性能急剧下降；组合索引让查询时间稳定在毫秒级。
 */
@Entity(
    tableName = "timer_sessions",
    indices = [
        Index(
            value = ["status", "phase", "endedAtEpochMillis"],
            name = "idx_status_phase_ended"
        )
    ]
)
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
 *
 * v0.2 P2: AGENTS 要求 completed sessions 能区分 completed / early-finished / interrupted。
 * 旧版 `COMPLETED` 包含"自然到点"+"提前结束"两种，本枚举把后者拆为 [EARLY_FINISHED]，
 * DAO 查询可分别统计"完整专注分钟数"vs"提前结束分钟数"。
 */
enum class SessionStatus {
    /** 阶段完整跑完（自然到点） */
    COMPLETED,

    /** 用户主动提前结束（计时未跑完，用户手动点"提前完成"） */
    EARLY_FINISHED,

    /** 用户放弃（"放弃" / 切换阶段中断） */
    INTERRUPTED,

    /** 跳过休息 */
    SKIPPED
}
