package com.pomotick.timer

import com.pomotick.data.SessionStatus
import com.pomotick.data.TimerSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TimerEngine 单测——状态机正确性。
 *
 * Engine 是纯函数，**不**依赖任何 Context / DataStore / Room。
 */
class TimerEngineTest {

    private val NOW = 1_000_000L
    private val PLANNED = 25L * 60L * 1000L
    private val TARGET_END = NOW + PLANNED

    private fun running(
        sessionId: Long = 1L,
        completionRecorded: Boolean = false
    ) = TimerRuntimeState(
        sessionId = sessionId,
        phase = TimerPhase.FOCUS,
        runState = TimerRunState.RUNNING,
        plannedDurationMillis = PLANNED,
        startedAtEpochMillis = NOW,
        targetEndAtEpochMillis = TARGET_END,
        pausedAtEpochMillis = null,
        accumulatedPausedMillis = 0L,
        extensionCount = 0,
        sessionCompletionRecorded = completionRecorded
    )

    @Test
    fun `start creates RUNNING state with sessionCompletionRecorded false`() {
        val result = TimerEngine.process(
            TimerEvent.Start(NOW, TimerPhase.FOCUS, PLANNED),
            current = null,
            now = NOW
        )
        val state = result.newState
        assertNotNull(state)
        assertEquals(TimerPhase.FOCUS, state!!.phase)
        assertEquals(TimerRunState.RUNNING, state.runState)
        assertEquals(NOW, state.startedAtEpochMillis)
        assertEquals(TARGET_END, state.targetEndAtEpochMillis)
        assertEquals(PLANNED, state.plannedDurationMillis)
        assertEquals(0, state.extensionCount)
        assertFalse(state.sessionCompletionRecorded)

        val effectTypes = result.effects.map { it::class.simpleName }
        assertTrue(effectTypes.contains("SaveRuntime"))
        assertTrue(effectTypes.contains("StartForegroundService"))
        assertTrue(effectTypes.contains("UpdateNotification"))
    }

    @Test
    fun `pause from RUNNING sets pausedAt`() {
        val result = TimerEngine.process(TimerEvent.Pause(NOW + 1000L), running(), NOW + 1000L)
        val state = result.newState!!
        assertEquals(TimerRunState.PAUSED, state.runState)
        assertEquals(NOW + 1000L, state.pausedAtEpochMillis)
    }

    @Test
    fun `resume from PAUSED extends targetEnd by pause duration`() {
        val pausedAt = NOW + 30_000L
        val resumeAt = NOW + 60_000L
        val paused = running().copy(
            runState = TimerRunState.PAUSED,
            pausedAtEpochMillis = pausedAt
        )
        val result = TimerEngine.process(TimerEvent.Resume(resumeAt), paused, resumeAt)
        val state = result.newState!!
        assertEquals(TimerRunState.RUNNING, state.runState)
        assertNull(state.pausedAtEpochMillis)
        assertEquals(30_000L, state.accumulatedPausedMillis)
        assertEquals(TARGET_END + 30_000L, state.targetEndAtEpochMillis)
    }

    @Test
    fun `extend adds 5 minutes and increments count`() {
        val result = TimerEngine.process(
            TimerEvent.Extend(NOW, deltaMs = 5L * 60L * 1000L),
            running(),
            NOW
        )
        val state = result.newState!!
        assertEquals(TARGET_END + 5L * 60L * 1000L, state.targetEndAtEpochMillis)
        assertEquals(1, state.extensionCount)
    }

    @Test
    fun `tick when now exceeds targetEnd transitions to RINGING with StartReminder`() {
        val result = TimerEngine.process(
            TimerEvent.OnTick(TARGET_END + 1_000L),
            running(),
            TARGET_END + 1_000L
        )
        assertEquals(TimerRunState.RINGING, result.newState!!.runState)
        // 验证不写 session（自然到点不立即入库）
        assertFalse(result.effects.any { it is TimerEffect.RecordSession })
        // 验证 StartReminder effect 被发出
        assertTrue(result.effects.any { it is TimerEffect.StartReminder })
    }

    @Test
    fun `tick when still within duration keeps RUNNING and emits UpdateNotification`() {
        val midNow = NOW + PLANNED / 2
        val result = TimerEngine.process(TimerEvent.OnTick(midNow), running(), midNow)
        assertEquals(TimerRunState.RUNNING, result.newState!!.runState)
        val updateEffect = result.effects.filterIsInstance<TimerEffect.UpdateNotification>().firstOrNull()
        assertNotNull(updateEffect)
        assertEquals(PLANNED / 2, updateEffect!!.remainingMs)
    }

    // ===== Issue #4: FinishEarly vs Respond.KnowIt 重复写测试 =====

    @Test
    fun `finishEarly records COMPLETED session and transitions to RINGING with completionRecorded true`() {
        val finishNow = NOW + 10L * 60L * 1000L
        val result = TimerEngine.process(TimerEvent.FinishEarly(finishNow), running(), finishNow)
        val state = result.newState!!
        assertEquals(TimerRunState.RINGING, state.runState)
        assertTrue("sessionCompletionRecorded must be true after FinishEarly", state.sessionCompletionRecorded)

        val recordEffect = result.effects.filterIsInstance<TimerEffect.RecordSession>().firstOrNull()
        assertNotNull("Expected RecordSession effect on FinishEarly", recordEffect)
        assertEquals(SessionStatus.COMPLETED, recordEffect!!.session.status)
        assertEquals(10L * 60L * 1000L, recordEffect.session.actualFocusMillis)
        assertEquals(finishNow, recordEffect.session.endedAtEpochMillis)
    }

    @Test
    fun `respond KnowIt after FinishEarly does NOT write session again (no duplicate)`() {
        // 1. 模拟 FinishEarly 后状态：RINGING + completionRecorded = true
        val ringingAfterFinish = running().copy(
            runState = TimerRunState.RINGING,
            sessionCompletionRecorded = true
        )
        val result = TimerEngine.process(
            TimerEvent.Respond(TARGET_END + 1000L, ResponseAction.KnowIt),
            ringingAfterFinish,
            TARGET_END + 1000L
        )
        assertNull(result.newState)

        // 关键：不应该有 RecordSession effect
        val recordEffects = result.effects.filterIsInstance<TimerEffect.RecordSession>()
        assertTrue(
            "Respond.KnowIt after FinishEarly must NOT write session again. " +
                "Effects: ${result.effects.map { it::class.simpleName }}",
            recordEffects.isEmpty()
        )
        // 仍应清 runtime + 停服务 + 停震动
        val effectTypes = result.effects.map { it::class.simpleName }
        assertTrue(effectTypes.contains("ClearRuntime"))
        assertTrue(effectTypes.contains("StopForegroundService"))
        assertTrue(effectTypes.contains("StopReminder"))
    }

    @Test
    fun `respond KnowIt after natural RINGING writes session exactly once`() {
        // 自然到点：RINGING + completionRecorded = false
        val naturalRinging = running().copy(
            runState = TimerRunState.RINGING,
            sessionCompletionRecorded = false
        )
        val result = TimerEngine.process(
            TimerEvent.Respond(TARGET_END + 1000L, ResponseAction.KnowIt),
            naturalRinging,
            TARGET_END + 1000L
        )
        assertNull(result.newState)

        val recordEffects = result.effects.filterIsInstance<TimerEffect.RecordSession>()
        assertEquals("Natural RINGING → KnowIt must write exactly 1 session", 1, recordEffects.size)
        assertEquals(SessionStatus.COMPLETED, recordEffects[0].session.status)
    }

    @Test
    fun `respond StartBreak after natural RINGING writes session and starts break`() {
        // 自然到点：RINGING + completionRecorded = false
        val naturalRinging = running().copy(
            runState = TimerRunState.RINGING,
            sessionCompletionRecorded = false
        )
        val options = RespondOptions(
            nextPhase = TimerPhase.LONG_BREAK,
            plannedMs = 15L * 60L * 1000L
        )
        val result = TimerEngine.process(
            TimerEvent.Respond(TARGET_END, ResponseAction.StartBreak, options),
            naturalRinging,
            TARGET_END
        )
        val newState = result.newState!!
        assertEquals(TimerRunState.RUNNING, newState.runState)
        assertEquals(TimerPhase.LONG_BREAK, newState.phase)
        assertEquals(15L * 60L * 1000L, newState.plannedDurationMillis)
        assertNotEquals(naturalRinging.sessionId, newState.sessionId)
        assertFalse(newState.sessionCompletionRecorded)

        // 关键：必须写一条 COMPLETED session
        val recordEffects = result.effects.filterIsInstance<TimerEffect.RecordSession>()
        assertEquals("Natural RINGING → StartBreak must write 1 session", 1, recordEffects.size)
        assertEquals(SessionStatus.COMPLETED, recordEffects[0].session.status)
    }

    @Test
    fun `respond StartBreak after FinishEarly does NOT write session again`() {
        // FinishEarly 后 → 用户点 StartBreak
        val ringingAfterFinish = running().copy(
            runState = TimerRunState.RINGING,
            sessionCompletionRecorded = true
        )
        val options = RespondOptions(
            nextPhase = TimerPhase.SHORT_BREAK,
            plannedMs = 5L * 60L * 1000L
        )
        val result = TimerEngine.process(
            TimerEvent.Respond(TARGET_END, ResponseAction.StartBreak, options),
            ringingAfterFinish,
            TARGET_END
        )
        val recordEffects = result.effects.filterIsInstance<TimerEffect.RecordSession>()
        assertTrue(
            "FinishEarly → StartBreak must NOT write session again",
            recordEffects.isEmpty()
        )
    }

    @Test
    fun `respond StartBreak without options uses SHORT_BREAK default`() {
        val naturalRinging = running().copy(
            runState = TimerRunState.RINGING,
            sessionCompletionRecorded = false
        )
        val result = TimerEngine.process(
            TimerEvent.Respond(TARGET_END, ResponseAction.StartBreak),  // 无 options
            naturalRinging,
            TARGET_END
        )
        val newState = result.newState!!
        assertEquals(TimerPhase.SHORT_BREAK, newState.phase)
        assertEquals(5L * 60L * 1000L, newState.plannedDurationMillis)
    }

    @Test
    fun `abandon records INTERRUPTED and returns to IDLE`() {
        val abandonNow = NOW + 5L * 60L * 1000L
        val result = TimerEngine.process(TimerEvent.Abandon(abandonNow), running(), abandonNow)
        assertNull("Expected newState to be null (IDLE)", result.newState)

        val recordEffect = result.effects.filterIsInstance<TimerEffect.RecordSession>().firstOrNull()
        assertNotNull(recordEffect)
        assertEquals(SessionStatus.INTERRUPTED, recordEffect!!.session.status)

        val effectTypes = result.effects.map { it::class.simpleName }
        assertTrue(effectTypes.contains("ClearRuntime"))
        assertTrue(effectTypes.contains("StopForegroundService"))
        assertTrue(effectTypes.contains("StopReminder"))
    }

    @Test
    fun `nextPhase returns SHORT_BREAK when fewer than 4 recent focus completed`() {
        val history = (1..3).map {
            TimerSession(
                id = it.toLong(),
                phase = TimerPhase.FOCUS,
                startedAtEpochMillis = NOW - it * 1000L,
                endedAtEpochMillis = NOW - it * 1000L + PLANNED,
                plannedDurationMillis = PLANNED,
                actualFocusMillis = PLANNED,
                status = SessionStatus.COMPLETED,
                extensionCount = 0
            )
        }
        assertEquals(TimerPhase.SHORT_BREAK, TimerEngine.nextPhase(history))
    }

    @Test
    fun `nextPhase returns LONG_BREAK when exactly 4 recent focus completed`() {
        val history = (1..4).map {
            TimerSession(
                id = it.toLong(),
                phase = TimerPhase.FOCUS,
                startedAtEpochMillis = NOW - it * 1000L,
                endedAtEpochMillis = NOW - it * 1000L + PLANNED,
                plannedDurationMillis = PLANNED,
                actualFocusMillis = PLANNED,
                status = SessionStatus.COMPLETED,
                extensionCount = 0
            )
        }
        assertEquals(TimerPhase.LONG_BREAK, TimerEngine.nextPhase(history))
    }

    // ===== 启动恢复测试 =====

    @Test
    fun `recovery OnTick on RUNNING past target transitions to RINGING with reminder`() {
        // 模拟 APP 重启：state 已从 DataStore 加载，仍为 RUNNING
        val running = running().copy(
            runState = TimerRunState.RUNNING,
            sessionCompletionRecorded = false  // 自然到点不立即入库
        )
        // 当前时间已超过 targetEnd（应用重启花了 1 分钟）
        val recoveredNow = TARGET_END + 60_000L
        val result = TimerEngine.process(TimerEvent.OnTick(recoveredNow), running, recoveredNow)
        val state = result.newState!!
        assertEquals(TimerRunState.RINGING, state.runState)
        assertFalse("自然到点的 session 不应标记已写", state.sessionCompletionRecorded)

        // 关键：必须发出 StartReminder（让 Service / 全局 handler 触发震动 + 通知）
        assertTrue("恢复路径必须发出 StartReminder", result.effects.any { it is TimerEffect.StartReminder })
        // 必须 SaveRuntime（持久化新状态）
        assertTrue(result.effects.any { it is TimerEffect.SaveRuntime })
        // 不应立即写 RecordSession（保持 user→KnowIt/StartBreak 才会写）
        assertFalse(result.effects.any { it is TimerEffect.RecordSession })
    }

    @Test
    fun `recovery OnTick on RUNNING not yet past target stays RUNNING`() {
        val running = running().copy(runState = TimerRunState.RUNNING)
        val midNow = NOW + PLANNED / 2  // 还在计时中
        val result = TimerEngine.process(TimerEvent.OnTick(midNow), running, midNow)
        assertEquals(TimerRunState.RUNNING, result.newState!!.runState)
        // 不发出 StartReminder
        assertFalse(result.effects.any { it is TimerEffect.StartReminder })
    }

    @Test
    fun `recovery OnTick on PAUSED does NOT auto-transition`() {
        // PAUSED 是有效状态，Engine 不应自动恢复
        val paused = running().copy(
            runState = TimerRunState.PAUSED,
            pausedAtEpochMillis = NOW + 5_000L
        )
        val longAfter = NOW + 10L * 60L * 1000L  // 暂停后过了 10 分钟
        val result = TimerEngine.process(TimerEvent.OnTick(longAfter), paused, longAfter)
        assertEquals(TimerRunState.PAUSED, result.newState!!.runState)
        assertFalse(result.effects.any { it is TimerEffect.StartReminder })
    }

    @Test
    fun `recovery OnTick on existing RINGING does NOT restart reminder`() {
        // 已在 RINGING 状态（用户响应前）→ 不应重复 StartReminder
        val ringing = running().copy(
            runState = TimerRunState.RINGING,
            sessionCompletionRecorded = false
        )
        val result = TimerEngine.process(TimerEvent.OnTick(TARGET_END + 1000L), ringing, TARGET_END + 1000L)
        assertEquals(TimerRunState.RINGING, result.newState!!.runState)
        assertFalse(
            "已 RINGING 状态不应再次 StartReminder（避免重置提醒节奏）",
            result.effects.any { it is TimerEffect.StartReminder }
        )
    }

    @Test
    fun `nextPhase ignores INTERRUPTED and SKIPPED`() {
        val history = listOf(
            TimerSession(phase = TimerPhase.FOCUS, startedAtEpochMillis = 0L, endedAtEpochMillis = PLANNED,
                plannedDurationMillis = PLANNED, actualFocusMillis = PLANNED, status = SessionStatus.COMPLETED),
            TimerSession(phase = TimerPhase.FOCUS, startedAtEpochMillis = 0L, endedAtEpochMillis = PLANNED,
                plannedDurationMillis = PLANNED, actualFocusMillis = PLANNED / 2, status = SessionStatus.INTERRUPTED),
            TimerSession(phase = TimerPhase.SHORT_BREAK, startedAtEpochMillis = 0L, endedAtEpochMillis = PLANNED,
                plannedDurationMillis = PLANNED, actualFocusMillis = PLANNED, status = SessionStatus.SKIPPED)
        )
        // 只有 1 个 COMPLETED FOCUS < 4 → SHORT_BREAK
        assertEquals(TimerPhase.SHORT_BREAK, TimerEngine.nextPhase(history))
    }
}
