package com.pomotick.timer

import org.junit.Assert.assertEquals
import org.junit.Test

class RemainingMillisTest {

    private val NOW = 1_000_000L
    private val PLANNED = 25L * 60L * 1000L
    private val TARGET_END = NOW + PLANNED

    private fun state(
        runState: TimerRunState = TimerRunState.RUNNING,
        pausedAt: Long? = null
    ) = TimerRuntimeState(
        sessionId = 1L,
        phase = TimerPhase.FOCUS,
        runState = runState,
        plannedDurationMillis = PLANNED,
        startedAtEpochMillis = NOW,
        targetEndAtEpochMillis = TARGET_END,
        pausedAtEpochMillis = pausedAt,
        accumulatedPausedMillis = 0L,
        extensionCount = 0,
        sessionCompletionRecorded = false
    )

    @Test
    fun `RUNNING remaining equals targetEnd - now`() {
        val midNow = NOW + PLANNED / 2
        assertEquals(PLANNED / 2, remainingMillis(midNow, state()))
    }

    @Test
    fun `PAUSED remaining is frozen at pausedAt`() {
        val pausedAt = NOW + 100_000L
        val frozen1 = remainingMillis(NOW + 500_000L, state(TimerRunState.PAUSED, pausedAt))
        val frozen2 = remainingMillis(NOW + 999_999L, state(TimerRunState.PAUSED, pausedAt))
        assertEquals(frozen1, frozen2)
        assertEquals(PLANNED - 100_000L, frozen1)
    }

    @Test
    fun `RINGING remaining equals targetEnd - now (can be zero)`() {
        assertEquals(0L, remainingMillis(TARGET_END, state(TimerRunState.RINGING)))
        assertEquals(0L, remainingMillis(TARGET_END + 1000L, state(TimerRunState.RINGING)))
    }

    // ===== Issue #5: actualFocusMillis 封顶 + PAUSED 冻结 =====

    @Test
    fun `actualFocusMillis caps at targetEnd during RUNNING (no growth past planned)`() {
        // 已经跑了 30 分钟（超过 25min planned）
        val wayPastNow = NOW + 30L * 60L * 1000L
        val actual = actualFocusMillis(wayPastNow, state(TimerRunState.RUNNING))
        assertEquals("必须封顶在 plannedDurationMillis（targetEnd 包含延长前为 PLANNED）",
            PLANNED, actual)
    }

    @Test
    fun `actualFocusMillis caps at targetEnd during RINGING (no growth past planned)`() {
        // 用户晚 10 分钟响应
        val lateNow = TARGET_END + 10L * 60L * 1000L
        val actual = actualFocusMillis(lateNow, state(TimerRunState.RINGING))
        assertEquals("RINGING 时等待时间不应算进专注时长",
            PLANNED, actual)
    }

    @Test
    fun `actualFocusMillis is FROZEN at pausedAt during PAUSED`() {
        val pausedAt = NOW + 5L * 60L * 1000L  // 已暂停在 5min 时刻
        // 即便 now 过了 1 小时，实际专注也应冻结在 5min
        val actual = actualFocusMillis(NOW + 60L * 60L * 1000L, state(TimerRunState.PAUSED, pausedAt))
        assertEquals("PAUSED 期间实际专注应冻结在 pausedAt",
            5L * 60L * 1000L, actual)
    }

    @Test
    fun `actualFocusMillis excludes paused time during RUNNING after resume`() {
        // 暂停 30s 后恢复：accumulatedPausedMillis = 30s
        val state = TimerRuntimeState(
            sessionId = 1L,
            phase = TimerPhase.FOCUS,
            runState = TimerRunState.RUNNING,
            plannedDurationMillis = PLANNED,
            startedAtEpochMillis = NOW,
            targetEndAtEpochMillis = TARGET_END + 30_000L,  // 延长 30s
            pausedAtEpochMillis = null,
            accumulatedPausedMillis = 30_000L,
            extensionCount = 0,
            sessionCompletionRecorded = false
        )
        val midNow = NOW + 10L * 60L * 1000L  // 从 now 起跑了 10 分钟
        val actual = actualFocusMillis(midNow, state)
        assertEquals(10L * 60L * 1000L - 30_000L, actual)
    }
}
