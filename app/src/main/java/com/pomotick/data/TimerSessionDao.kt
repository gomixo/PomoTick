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
     * 今日已完成 FOCUS 累计专注毫秒。
     */
    @Query(
        """
        SELECT COALESCE(SUM(actualFocusMillis), 0) FROM timer_sessions
        WHERE status = 'COMPLETED'
          AND phase = 'FOCUS'
          AND endedAtEpochMillis IS NOT NULL
          AND endedAtEpochMillis >= :dayStart
        """
    )
    suspend fun sumFocusMillisSince(dayStart: Long): Long

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
