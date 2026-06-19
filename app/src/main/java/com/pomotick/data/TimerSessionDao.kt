package com.pomotick.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 历史番茄 session 数据访问对象。
 */
@Dao
interface TimerSessionDao {

    /** 插入一条 session 并返回自增 id */
    @Insert
    suspend fun insert(session: TimerSession): Long

    /**
     * 今日已完成 FOCUS 数（仅 status = COMPLETED）。
     *
     * @param dayStart 当日 00:00 的 epoch millis（调用方按本地时区计算）。
     */
    @Query(
        """
        SELECT COUNT(*) FROM timer_sessions
        WHERE status = 'COMPLETED'
          AND phase = 'FOCUS'
          AND endedAtEpochMillis IS NOT NULL
          AND endedAtEpochMillis >= :dayStart
        """
    )
    suspend fun countCompletedFocusSince(dayStart: Long): Int

    /**
     * 今日累计专注毫秒（含 EARLY_FINISHED）。
     *
     * v0.2 第三轮 P2.3 决策：提前结束的实际专注时长也计入"今日专注总时间"，
     * 因为用户主动 Stop 之前通常已经完成了实质专注。完成次数 [countCompletedFocusSince]
     * 仍只算 COMPLETED——避免"提前结束"污染"完成 N 个番茄"这种成就统计。
     *
     * 注：若产品决定 EARLY_FINISHED 不计入统计，把下面的 `'EARLY_FINISHED'` 从列表移除即可。
     */
    @Query(
        """
        SELECT COALESCE(SUM(actualFocusMillis), 0) FROM timer_sessions
        WHERE status IN ('COMPLETED', 'EARLY_FINISHED')
          AND phase = 'FOCUS'
          AND endedAtEpochMillis IS NOT NULL
          AND endedAtEpochMillis >= :dayStart
        """
    )
    suspend fun sumFocusMillisSince(dayStart: Long): Long

    /**
     * v0.2 §8: 区间 [start, end) 内累计专注毫秒（含 EARLY_FINISHED）。
     * 用于按时间段拆分（凌晨/上午/下午/晚间）和周聚合。
     */
    @Query(
        """
        SELECT COALESCE(SUM(actualFocusMillis), 0) FROM timer_sessions
        WHERE status IN ('COMPLETED', 'EARLY_FINISHED')
          AND phase = 'FOCUS'
          AND endedAtEpochMillis IS NOT NULL
          AND endedAtEpochMillis >= :start
          AND endedAtEpochMillis < :end
        """
    )
    suspend fun sumFocusMillisBetween(start: Long, end: Long): Long

    /**
     * v0.2 §8: 区间 [start, end) 内累计休息毫秒（SHORT_BREAK + LONG_BREAK，含 EARLY_FINISHED）。
     */
    @Query(
        """
        SELECT COALESCE(SUM(actualFocusMillis), 0) FROM timer_sessions
        WHERE status IN ('COMPLETED', 'EARLY_FINISHED')
          AND phase IN ('SHORT_BREAK', 'LONG_BREAK')
          AND endedAtEpochMillis IS NOT NULL
          AND endedAtEpochMillis >= :start
          AND endedAtEpochMillis < :end
        """
    )
    suspend fun sumBreakMillisBetween(start: Long, end: Long): Long

    /**
     * v0.2 第五轮 P0 性能修复：把"最近 7 天每日专注毫秒"从 7 次 sequential 查询
     * 合并为 1 条 SQL。返回 [(dayStartEpochMillis, focusMillis), ...]，按天升序。
     *
     * 原实现：
     * ```
     * (0..6).map { offset -> sumFocusMillisBetween(dayStart - offset * 1d, dayStart - (offset-1) * 1d) }
     * ```
     * 即使按并行优化（async），仍是 7 次 IO；这里用 `GROUP BY (endedAtEpochMillis / dayMillis)`
     * 直接一次扫表 + 一次 group by 拿全周聚合。
     *
     * `endedAtEpochMillis / :dayMillis` 把 epoch 毫秒转成"今日从 00:00 起算的第 N 天"，
     * `+ :todayStart / :dayMillis` 把负数偏移回正。`dayStartEpochMillis` 是
     * `(dayBucket * :dayMillis) - (:todayStart / :dayMillis) * :dayMillis + :todayStart`。
     *
     * 用 `(endedAtEpochMillis - :offsetMillis) / :dayMillis` 简化：让数据库把所有
     * 早于 `todayStart` 的 session 也归到对应 dayStart，调用方根据 dayStart 反推 label。
     */
    @Query(
        """
        SELECT
            ((endedAtEpochMillis - :todayStart) / :dayMillis) AS dayOffset,
            COALESCE(SUM(actualFocusMillis), 0) AS focusMillis
        FROM timer_sessions
        WHERE status IN ('COMPLETED', 'EARLY_FINISHED')
          AND phase = 'FOCUS'
          AND endedAtEpochMillis IS NOT NULL
          AND endedAtEpochMillis >= :weekStart
          AND endedAtEpochMillis < :dayAfterEnd
        GROUP BY dayOffset
        ORDER BY dayOffset ASC
        """
    )
    suspend fun sumFocusMillisGroupedByDay(
        todayStart: Long,
        dayMillis: Long,
        weekStart: Long,
        dayAfterEnd: Long
    ): List<DailyFocusAggregate>

    /**
     * 最近一条已完成 FOCUS（用于"最近完成时间"展示）。
     */
    @Query(
        """
        SELECT * FROM timer_sessions
        WHERE status = 'COMPLETED'
          AND phase = 'FOCUS'
          AND endedAtEpochMillis IS NOT NULL
        ORDER BY endedAtEpochMillis DESC
        LIMIT 1
        """
    )
    suspend fun latestCompletedFocus(): TimerSession?

    /**
     * 最近 N 条已完成 FOCUS session（按 endedAtEpochMillis 倒序），用于 LONG_BREAK 计数。
     */
    @Query(
        """
        SELECT * FROM timer_sessions
        WHERE status = 'COMPLETED'
          AND phase = 'FOCUS'
          AND endedAtEpochMillis IS NOT NULL
        ORDER BY endedAtEpochMillis DESC
        LIMIT :limit
        """
    )
    suspend fun recentCompletedFocus(limit: Int): List<TimerSession>

    /** 监听最新一条已完成 FOCUS（Compose 实时刷新用） */
    @Query(
        """
        SELECT * FROM timer_sessions
        WHERE status = 'COMPLETED'
          AND phase = 'FOCUS'
          AND endedAtEpochMillis IS NOT NULL
        ORDER BY endedAtEpochMillis DESC
        LIMIT 1
        """
    )
    fun observeLatestCompletedFocus(): Flow<TimerSession?>
}
