package com.pomotick.data

import androidx.room.ColumnInfo

/**
 * v0.2 第五轮 P0 性能修复：[TimerSessionDao.sumFocusMillisGroupedByDay] 的返回类型。
 *
 * Room 不能直接返回 `(Long, Long)` 元组，所以用一个不可变 data class 承载两列：
 * - `dayOffset`：从 `todayStart` 算起的天数偏移（0 = 今天，1 = 昨天，-1 = 明天，理论上不会出现）
 * - `focusMillis`：该日 FOCUS 累计毫秒（含 EARLY_FINISHED）
 *
 * 调用方用 `dayStart = todayStart + dayOffset * dayMillis` 反算这一天的 00:00 边界。
 */
data class DailyFocusAggregate(
    @ColumnInfo(name = "dayOffset")
    val dayOffset: Long,
    @ColumnInfo(name = "focusMillis")
    val focusMillis: Long
)
