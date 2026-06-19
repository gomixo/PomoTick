package com.pomotick

import com.pomotick.timer.TimerPhase
import com.pomotick.timer.TimerRunState
import com.pomotick.timer.TimerRuntimeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.1 P1 修复单测：[PomoTickApp.shouldScheduleAlarm] 的过期守卫逻辑。
 *
 * 关键场景（避免给已过期 RUNNING 重复注册"立即触发"的 alarm）：
 * - RUNNING + targetEnd > now   → true
 * - RUNNING + targetEnd == now  → false（边界）
 * - RUNNING + targetEnd < now   → false（过期）
 * - PAUSED                      → false
 * - RINGING                     → false
 * - IDLE / FINISHED             → false
 * - null                        → false
 */
class PomoTickAppTest {

    private val now = 1_000_000L

    private fun runtime(
        runState: TimerRunState,
        targetEnd: Long = now + 60_000L
    ): TimerRuntimeState = TimerRuntimeState(
        sessionId = 1L,
        phase = TimerPhase.FOCUS,
        runState = runState,
        plannedDurationMillis = 25L * 60L * 1000L,
        startedAtEpochMillis = now - 60_000L,
        targetEndAtEpochMillis = targetEnd,
        pausedAtEpochMillis = null,
        accumulatedPausedMillis = 0L,
        extensionCount = 0,
        sessionCompletionRecorded = false
    )

    @Test
    fun `RUNNING with future targetEnd returns true`() {
        assertTrue(
            PomoTickApp.shouldScheduleAlarm(runtime(TimerRunState.RUNNING), now)
        )
    }

    @Test
    fun `RUNNING with targetEnd exactly now returns false (boundary)`() {
        assertFalse(
            PomoTickApp.shouldScheduleAlarm(
                runtime(TimerRunState.RUNNING, targetEnd = now),
                now
            )
        )
    }

    @Test
    fun `RUNNING with expired targetEnd returns false (P1 core fix)`() {
        assertFalse(
            PomoTickApp.shouldScheduleAlarm(
                runtime(TimerRunState.RUNNING, targetEnd = now - 1L),
                now
            )
        )
    }

    @Test
    fun `PAUSED returns false`() {
        assertFalse(
            PomoTickApp.shouldScheduleAlarm(runtime(TimerRunState.PAUSED), now)
        )
    }

    @Test
    fun `RINGING returns false`() {
        assertFalse(
            PomoTickApp.shouldScheduleAlarm(runtime(TimerRunState.RINGING), now)
        )
    }

    @Test
    fun `IDLE returns false`() {
        assertFalse(
            PomoTickApp.shouldScheduleAlarm(runtime(TimerRunState.IDLE), now)
        )
    }

    @Test
    fun `FINISHED returns false`() {
        assertFalse(
            PomoTickApp.shouldScheduleAlarm(runtime(TimerRunState.FINISHED), now)
        )
    }

    @Test
    fun `null state returns false`() {
        assertFalse(PomoTickApp.shouldScheduleAlarm(null, now))
    }
}