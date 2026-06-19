package com.pomotick.timer

import com.pomotick.data.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2 单测覆盖范围：
 *
 * - 基础状态机迁移（Start / Pause / Resume / Extend / Abandon / FinishEarly / Reset）
 * - §6 轮次推进（computeNextPhase / durationFor）
 * - §4 重复提醒调度（scheduleRepeatReminder：30s 进入窗口 / 3min 触发重复 / 重复只 1 次）
 * - §9 持久化恢复（Start 写入 5 个快照字段，OnTick 进入 RINGING 写入 §4 字段）
 * - P1 拆分 StopRingingOnly（仅停声震、保持 RINGING、启动等待窗口）
 * - P1 拆分 StopRingingAndPrepareNext（停声震 + 写 COMPLETED + AdvanceCycle + 进入下一阶段）
 */
class TimerEngineTest {

    companion object {
        const val NOW = 1_700_000_000_000L
        const val TARGET_END = NOW + 25L * 60L * 1000L
        const val FOCUS_MS = 25L * 60L * 1000L
        const val SHORT_BREAK_MS = 5L * 60L * 1000L
        const val LONG_BREAK_MS = 15L * 60L * 1000L
    }

    // ===== 基础迁移 =====

    @Test
    fun `start creates running state with snapshot fields`() {
        val r = TimerEngine.process(
            TimerEvent.Start(
                now = NOW,
                phase = TimerPhase.FOCUS,
                plannedMs = FOCUS_MS,
                cyclePositionAtStart = 1,
                longBreakMinutesAtStart = 20,
                shortBreakMinutesAtStart = 6,
                focusMinutesAtStart = 30,
                cyclesBeforeLongBreakAtStart = 4
            ),
            current = null,
            now = NOW
        )
        val state = r.newState!!
        assertEquals(TimerRunState.RUNNING, state.runState)
        assertEquals(TimerPhase.FOCUS, state.phase)
        assertEquals(TARGET_END, state.targetEndAtEpochMillis)
        // 5 个快照字段都已写入
        assertEquals(1, state.cyclePositionAtStart)
        assertEquals(20, state.longBreakMinutesAtStart)
        assertEquals(6, state.shortBreakMinutesAtStart)
        assertEquals(30, state.focusMinutesAtStart)
        assertEquals(4, state.cyclesBeforeLongBreakAtStart)
        // §4 字段全部重置
        assertNull(state.ringingStartedAtEpochMillis)
        assertNull(state.awaitingRepeatSinceEpochMillis)
        assertFalse(state.repeatReminderFired)
    }

    @Test
    fun `pause and resume preserve target_end by adding pause duration`() {
        val started = TimerEngine.process(
            TimerEvent.Start(NOW, TimerPhase.FOCUS, FOCUS_MS), null, NOW
        ).newState!!
        val paused = TimerEngine.process(
            TimerEvent.Pause(NOW + 60_000L), started, NOW + 60_000L
        ).newState!!
        assertEquals(TimerRunState.PAUSED, paused.runState)
        assertEquals(NOW + 60_000L, paused.pausedAtEpochMillis)
        // resume 5 min later
        val resumed = TimerEngine.process(
            TimerEvent.Resume(NOW + 360_000L), paused, NOW + 360_000L
        ).newState!!
        assertEquals(TimerRunState.RUNNING, resumed.runState)
        assertNull(resumed.pausedAtEpochMillis)
        assertEquals(300_000L, resumed.accumulatedPausedMillis)
        // target_end 推迟了 5 分钟
        assertEquals(TARGET_END + 300_000L, resumed.targetEndAtEpochMillis)
    }

    @Test
    fun `extend adds 5 minutes to both planned and target_end`() {
        val started = TimerEngine.process(
            TimerEvent.Start(NOW, TimerPhase.FOCUS, FOCUS_MS), null, NOW
        ).newState!!
        val extended = TimerEngine.process(
            TimerEvent.Extend(NOW + 60_000L, 5L * 60L * 1000L),
            started, NOW + 60_000L
        ).newState!!
        assertEquals(FOCUS_MS + 5L * 60L * 1000L, extended.plannedDurationMillis)
        assertEquals(TARGET_END + 5L * 60L * 1000L, extended.targetEndAtEpochMillis)
        assertEquals(1, extended.extensionCount)
    }

    @Test
    fun `abandon clears runtime and records INTERRUPTED session`() {
        val started = TimerEngine.process(
            TimerEvent.Start(NOW, TimerPhase.FOCUS, FOCUS_MS), null, NOW
        ).newState!!
        val result = TimerEngine.process(
            TimerEvent.Abandon(NOW + 60_000L), started, NOW + 60_000L
        )
        assertNull(result.newState)
        val session = result.effects.filterIsInstance<TimerEffect.RecordSession>().first().session
        assertEquals(SessionStatus.INTERRUPTED, session.status)
        // 没有 AdvanceCycle
        assertTrue(result.effects.none { it is TimerEffect.AdvanceCycle })
    }

    // ===== §6 轮次推进 =====

    @Test
    fun `nextPhase - cycle position 0 of 3 (first FOCUS) returns SHORT_BREAK`() {
        val state = runningState(cyclePos = 0, cycles = 3, phase = TimerPhase.FOCUS)
        val next = TimerEngine.computeNextPhase(state, TimerPhase.FOCUS)
        // 完成后 posAfter=1 < 3
        assertEquals(TimerPhase.SHORT_BREAK, next)
    }

    @Test
    fun `nextPhase - cycle position 1 of 3 (second FOCUS) returns SHORT_BREAK`() {
        val state = runningState(cyclePos = 1, cycles = 3, phase = TimerPhase.FOCUS)
        val next = TimerEngine.computeNextPhase(state, TimerPhase.FOCUS)
        // 完成后 posAfter=2 < 3
        assertEquals(TimerPhase.SHORT_BREAK, next)
    }

    @Test
    fun `nextPhase - cycle position 2 of 3 (third FOCUS) returns LONG_BREAK`() {
        val state = runningState(cyclePos = 2, cycles = 3, phase = TimerPhase.FOCUS)
        val next = TimerEngine.computeNextPhase(state, TimerPhase.FOCUS)
        // 完成后 posAfter=3 >= 3
        assertEquals(TimerPhase.LONG_BREAK, next)
    }

    @Test
    fun `nextPhase - completing SHORT_BREAK returns FOCUS`() {
        val state = runningState(cyclePos = 1, cycles = 3, phase = TimerPhase.SHORT_BREAK)
        val next = TimerEngine.computeNextPhase(state, TimerPhase.SHORT_BREAK)
        assertEquals(TimerPhase.FOCUS, next)
    }

    @Test
    fun `nextPhase - completing LONG_BREAK returns FOCUS`() {
        val state = runningState(cyclePos = 3, cycles = 3, phase = TimerPhase.LONG_BREAK)
        val next = TimerEngine.computeNextPhase(state, TimerPhase.LONG_BREAK)
        assertEquals(TimerPhase.FOCUS, next)
    }

    @Test
    fun `durationFor uses runtime snapshot, not current settings`() {
        val state = runningState(
            focusMin = 30, shortMin = 6, longMin = 20, cycles = 3,
            cyclePos = 0, phase = TimerPhase.FOCUS
        )
        assertEquals(30L * 60_000L, TimerEngine.durationFor(state, TimerPhase.FOCUS))
        assertEquals(6L * 60_000L, TimerEngine.durationFor(state, TimerPhase.SHORT_BREAK))
        assertEquals(20L * 60_000L, TimerEngine.durationFor(state, TimerPhase.LONG_BREAK))
    }

    // ===== §4 重复提醒调度 =====

    @Test
    fun `OnTick RUNNING to RINGING sets ringingStartedAt and emits StartReminder 30s`() {
        val started = TimerEngine.process(
            TimerEvent.Start(NOW, TimerPhase.FOCUS, FOCUS_MS), null, NOW
        ).newState!!
        val r = TimerEngine.process(
            TimerEvent.OnTick(TARGET_END), started, TARGET_END
        )
        val ringing = r.newState!!
        assertEquals(TimerRunState.RINGING, ringing.runState)
        assertEquals(TARGET_END, ringing.ringingStartedAtEpochMillis)
        assertNull(ringing.awaitingRepeatSinceEpochMillis)
        assertFalse(ringing.repeatReminderFired)
        val startReminder = r.effects.filterIsInstance<TimerEffect.StartReminder>().first()
        assertEquals(TimerEngine.FIRST_REMINDER_DURATION_MS, startReminder.durationMs)
    }

    @Test
    fun `OnTick in RINGING 30s after enter sets awaitingRepeatSinceEpochMillis`() {
        val ringing = ringingState(NOW, ringingStartedAt = NOW)
        val r = TimerEngine.process(
            TimerEvent.OnTick(NOW + 30_000L), ringing, NOW + 30_000L
        )
        val state = r.newState!!
        assertEquals(NOW + 30_000L, state.awaitingRepeatSinceEpochMillis)
        // 有 SaveRuntime effect（确保重启可恢复）
        assertTrue(r.effects.any { it is TimerEffect.SaveRuntime })
    }

    @Test
    fun `OnTick in RINGING 3min after awaiting sets repeatReminderFired and emits 15s StartReminder`() {
        val awaiting = ringingState(
            NOW,
            ringingStartedAt = NOW - 60_000L,
            awaitingRepeatSince = NOW - 3L * 60L * 1000L
        )
        val r = TimerEngine.process(
            TimerEvent.OnTick(NOW), awaiting, NOW
        )
        val state = r.newState!!
        assertTrue(state.repeatReminderFired)
        assertNull(state.awaitingRepeatSinceEpochMillis)
        // 触发 15s 重复提醒
        val startReminder = r.effects.filterIsInstance<TimerEffect.StartReminder>().first()
        assertEquals(TimerEngine.REPEAT_REMINDER_DURATION_MS, startReminder.durationMs)
        // 有 SaveRuntime
        assertTrue(r.effects.any { it is TimerEffect.SaveRuntime })
    }

    @Test
    fun `repeat reminder only fires once - second OnTick after fired does not re-emit StartReminder`() {
        val fired = ringingState(
            NOW,
            ringingStartedAt = NOW - 5L * 60L * 1000L,
            awaitingRepeatSince = null,
            repeatReminderFired = true
        )
        val r = TimerEngine.process(
            TimerEvent.OnTick(NOW + 60_000L), fired, NOW + 60_000L
        )
        // 状态未变
        assertEquals(true, r.newState!!.repeatReminderFired)
        // 没有新 StartReminder（only UpdateNotification）
        assertTrue(r.effects.none { it is TimerEffect.StartReminder })
    }

    // ===== P1 StopRingingOnly（仅停声震，保持 RINGING） =====

    @Test
    fun `StopRingingOnly within 30s - immediately starts 3min wait window from now`() {
        // v0.2 P1.1：手动停止后立即把 awaitingRepeatSinceEpochMillis 锚到 now，
        // 不再依赖原 30s 阈值。否则第 5 秒停止 → 重复提醒在 3 分 30 秒后才触发。
        val ringing = ringingState(NOW, ringingStartedAt = NOW)
        val stoppedAt = NOW + 5_000L
        val r = TimerEngine.process(
            TimerEvent.StopRingingOnly(stoppedAt), ringing, stoppedAt
        )
        val state = r.newState!!
        // 仍 RINGING
        assertEquals(TimerRunState.RINGING, state.runState)
        // 等待窗口起点 = 停止时刻（不是 30s 阈值后）
        assertEquals(stoppedAt, state.awaitingRepeatSinceEpochMillis)
        // 有 StopReminder（停声震）
        assertTrue(r.effects.any { it is TimerEffect.StopReminder })
        // 没有 ClearRuntime / StopForegroundService
        assertTrue(r.effects.none { it is TimerEffect.ClearRuntime })
        assertTrue(r.effects.none { it is TimerEffect.StopForegroundService })
        // 持久化（让重启可恢复窗口）
        assertTrue(r.effects.any { it is TimerEffect.SaveRuntime })
    }

    @Test
    fun `StopRingingOnly after 30s - starts 3min wait window at stop time`() {
        val ringing = ringingState(NOW, ringingStartedAt = NOW)
        val stoppedAt = NOW + 60_000L
        val r = TimerEngine.process(
            TimerEvent.StopRingingOnly(stoppedAt), ringing, stoppedAt
        )
        val state = r.newState!!
        assertEquals(TimerRunState.RINGING, state.runState)
        assertEquals(stoppedAt, state.awaitingRepeatSinceEpochMillis)
        assertTrue(r.effects.any { it is TimerEffect.StopReminder })
        assertTrue(r.effects.any { it is TimerEffect.SaveRuntime })
    }

    @Test
    fun `StopRingingOnly after repeat already fired does not reset window`() {
        // 若 repeatReminderFired=true，等待窗口已经没有意义，不重新设置
        val ringing = ringingState(
            NOW, ringingStartedAt = NOW - 60_000L,
            awaitingRepeatSince = NOW - 1_000L,
            repeatReminderFired = true
        )
        val stoppedAt = NOW + 5_000L
        val r = TimerEngine.process(
            TimerEvent.StopRingingOnly(stoppedAt), ringing, stoppedAt
        )
        val state = r.newState!!
        // 不修改 awaitingRepeatSinceEpochMillis（保持之前的值）
        assertEquals(NOW - 1_000L, state.awaitingRepeatSinceEpochMillis)
    }

    // ===== P1 StopRingingAndPrepareNext（停声震 + 写 session + 推进轮次 + 进入下一阶段） =====

    @Test
    fun `StopRingingAndPrepareNext for FOCUS cycle 1 of 3 advances to SHORT_BREAK and emits AdvanceCycle`() {
        val ringing = ringingState(NOW, ringingStartedAt = NOW, phase = TimerPhase.FOCUS, cyclePos = 1, cycles = 3)
        val r = TimerEngine.process(
            TimerEvent.StopRingingAndPrepareNext(NOW), ringing, NOW
        )
        // runtime 清空
        assertNull(r.newState)
        // 写 COMPLETED session
        val session = r.effects.filterIsInstance<TimerEffect.RecordSession>().first().session
        assertEquals(SessionStatus.COMPLETED, session.status)
        assertEquals(TimerPhase.FOCUS, session.phase)
        // 发 AdvanceCycle(FOCUS)
        val advance = r.effects.filterIsInstance<TimerEffect.AdvanceCycle>().first()
        assertEquals(TimerPhase.FOCUS, advance.completedPhase)
        // SaveSelectedPhase(SHORT_BREAK) + ClearRuntime + StopForegroundService
        val savePhase = r.effects.filterIsInstance<TimerEffect.SaveSelectedPhase>().first()
        assertEquals(TimerPhase.SHORT_BREAK, savePhase.phase)
        assertTrue(r.effects.any { it is TimerEffect.ClearRuntime })
        assertTrue(r.effects.any { it is TimerEffect.StopForegroundService })
    }

    @Test
    fun `StopRingingAndPrepareNext for FOCUS cycle 2 of 3 advances to LONG_BREAK`() {
        // pos=2 of 3 means 3rd FOCUS of cycle; after completion posAfter=3 >= 3
        val ringing = ringingState(NOW, ringingStartedAt = NOW, phase = TimerPhase.FOCUS, cyclePos = 2, cycles = 3)
        val r = TimerEngine.process(
            TimerEvent.StopRingingAndPrepareNext(NOW), ringing, NOW
        )
        val savePhase = r.effects.filterIsInstance<TimerEffect.SaveSelectedPhase>().first()
        assertEquals(TimerPhase.LONG_BREAK, savePhase.phase)
    }

    @Test
    fun `StopRingingAndPrepareNext for LONG_BREAK resets cycle to 0 and goes to FOCUS`() {
        val ringing = ringingState(NOW, ringingStartedAt = NOW, phase = TimerPhase.LONG_BREAK, cyclePos = 3, cycles = 3)
        val r = TimerEngine.process(
            TimerEvent.StopRingingAndPrepareNext(NOW), ringing, NOW
        )
        val session = r.effects.filterIsInstance<TimerEffect.RecordSession>().first().session
        assertEquals(TimerPhase.LONG_BREAK, session.phase)
        val savePhase = r.effects.filterIsInstance<TimerEffect.SaveSelectedPhase>().first()
        assertEquals(TimerPhase.FOCUS, savePhase.phase)
        val advance = r.effects.filterIsInstance<TimerEffect.AdvanceCycle>().first()
        assertEquals(TimerPhase.LONG_BREAK, advance.completedPhase)
    }

    @Test
    fun `StopRingingAndPrepareNext after FinishEarly does not double-record session or advance cycle`() {
        // v0.2 P0 修复：FinishEarly 已经写过 EARLY_FINISHED session + AdvanceCycle，
        // 用户随后在提醒页点"知道了"不应该再写一条 COMPLETED + 再次 AdvanceCycle。
        val started = TimerEngine.process(
            TimerEvent.Start(NOW, TimerPhase.FOCUS, FOCUS_MS), null, NOW
        ).newState!!
        val finishedEarly = TimerEngine.process(
            TimerEvent.FinishEarly(NOW + 60_000L), started, NOW + 60_000L
        )
        val ringing = finishedEarly.newState!!
        // 验证 FinishEarly 路径：写 EARLY_FINISHED、推进轮次、标记已记录
        assertEquals(TimerRunState.RINGING, ringing.runState)
        assertTrue(ringing.sessionCompletionRecorded)
        assertEquals(1, finishedEarly.effects.count { it is TimerEffect.RecordSession })

        // 用户点"知道了" → StopRingingAndPrepareNext
        val r = TimerEngine.process(
            TimerEvent.StopRingingAndPrepareNext(NOW + 90_000L), ringing, NOW + 90_000L
        )
        // 关键：不再写第二条 session
        assertEquals(0, r.effects.count { it is TimerEffect.RecordSession })
        // 关键：不再发第二次 AdvanceCycle
        assertEquals(0, r.effects.count { it is TimerEffect.AdvanceCycle })
        // 但仍然切换到下一阶段（selected phase 更新 + 清 runtime + 停服务）
        assertTrue(r.effects.any { it is TimerEffect.SaveSelectedPhase })
        assertTrue(r.effects.any { it is TimerEffect.ClearRuntime })
        assertTrue(r.effects.any { it is TimerEffect.StopForegroundService })
    }

    // ===== §9 持久化恢复 =====

    @Test
    fun `OnTick RUNNING to RINGING emits SaveRuntime and StartForegroundService in order`() {
        val started = TimerEngine.process(
            TimerEvent.Start(NOW, TimerPhase.FOCUS, FOCUS_MS), null, NOW
        ).newState!!
        val r = TimerEngine.process(
            TimerEvent.OnTick(TARGET_END), started, TARGET_END
        )
        val effects = r.effects
        // 顺序：SaveRuntime → StartForegroundService → StartReminder → UpdateNotification
        assertTrue(effects[0] is TimerEffect.SaveRuntime)
        assertTrue(effects[1] is TimerEffect.StartForegroundService)
        assertTrue(effects[2] is TimerEffect.StartReminder)
        assertTrue(effects[3] is TimerEffect.UpdateNotification)
    }

    // ===== P2 区分完成/提前结束 =====

    @Test
    fun `FinishEarly records EARLY_FINISHED session and advances cycle`() {
        val started = TimerEngine.process(
            TimerEvent.Start(NOW, TimerPhase.FOCUS, FOCUS_MS), null, NOW
        ).newState!!
        val r = TimerEngine.process(
            TimerEvent.FinishEarly(NOW + 60_000L), started, NOW + 60_000L
        )
        val state = r.newState!!
        // 仍 RINGING（不直接清 runtime）
        assertEquals(TimerRunState.RINGING, state.runState)
        val session = r.effects.filterIsInstance<TimerEffect.RecordSession>().first().session
        // P2: 区分自然完成 vs 提前结束
        assertEquals(SessionStatus.EARLY_FINISHED, session.status)
        // 仍推进轮次
        assertTrue(r.effects.any { it is TimerEffect.AdvanceCycle })
    }

    @Test
    fun `FinishEarly in IDLE is a no-op - keeps state and emits no effects`() {
        // v0.2 P2.1 修复：非法事件保持当前 state + empty effects，不清内存 runtime。
        // current=null 时返回 idle（这之前的行为），由调用方理解语义。
        val r1 = TimerEngine.process(
            TimerEvent.FinishEarly(NOW), null, NOW
        )
        assertNull(r1.newState)
        assertTrue(r1.effects.isEmpty())
    }

    @Test
    fun `Pause in non-RUNNING state is a no-op - keeps current state and emits no effects`() {
        // v0.2 P2.1 修复：非法 Pause 不再把内存 runtime 置空。
        val ringing = ringingState(NOW, ringingStartedAt = NOW)
        val r = TimerEngine.process(TimerEvent.Pause(NOW + 1_000L), ringing, NOW + 1_000L)
        assertEquals(ringing, r.newState)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun `Resume in non-PAUSED state is a no-op - keeps current state and emits no effects`() {
        val running = runningState()
        val r = TimerEngine.process(TimerEvent.Resume(NOW + 1_000L), running, NOW + 1_000L)
        assertEquals(running, r.newState)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun `Extend in non-RUNNING state is a no-op - keeps current state and emits no effects`() {
        val ringing = ringingState(NOW, ringingStartedAt = NOW)
        val r = TimerEngine.process(TimerEvent.Extend(NOW + 1_000L, 60_000L), ringing, NOW + 1_000L)
        assertEquals(ringing, r.newState)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun `Respond in non-RINGING state is a no-op - keeps current state and emits no effects`() {
        val running = runningState()
        val r = TimerEngine.process(
            TimerEvent.Respond(NOW + 1_000L, ResponseAction.KnowIt), running, NOW + 1_000L
        )
        assertEquals(running, r.newState)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun `ContinueFocus only adds 5 minutes to target_end without re-adding planned duration`() {
        // v0.2 P2.2 修复：旧实现 now + plannedMs + EXTEND_FOCUS_MS 会让 25min 到点后
        // 变成 ~30min。新实现只追加 5min 到原 targetEnd。
        val ringing = ringingState(NOW, ringingStartedAt = NOW)
        val originalTarget = ringing.targetEndAtEpochMillis
        val extendNow = NOW + 1_000L
        val r = TimerEngine.process(
            TimerEvent.Respond(extendNow, ResponseAction.ContinueFocus), ringing, extendNow
        )
        val extended = r.newState!!
        assertEquals(TimerRunState.RUNNING, extended.runState)
        // targetEnd 只增加 5 分钟
        assertEquals(originalTarget + TimerEngine.EXTEND_FOCUS_MS, extended.targetEndAtEpochMillis)
        // plannedDuration +5min
        assertEquals(
            ringing.plannedDurationMillis + TimerEngine.EXTEND_FOCUS_MS,
            extended.plannedDurationMillis
        )
        // 后续允许重新响应（清除 completion 标志）
        assertFalse(extended.sessionCompletionRecorded)
    }

    @Test
    fun `ResumeReminder in RINGING emits StartReminder with remaining duration`() {
        // v0.2 P1.2 修复：冷启动恢复 RINGING 时按时间戳补一次有限时长提醒。
        val ringing = ringingState(NOW, ringingStartedAt = NOW)
        val r = TimerEngine.process(
            TimerEvent.ResumeReminder(NOW + 10_000L, ringing.phase, 20_000L),
            ringing,
            NOW + 10_000L
        )
        // 状态不变
        assertEquals(ringing, r.newState)
        // 只发一个 StartReminder
        val sr = r.effects.filterIsInstance<TimerEffect.StartReminder>().firstOrNull()
        assertNotNull(sr)
        assertEquals(20_000L, sr!!.durationMs)
        assertEquals(ringing.phase, sr.phase)
    }

    @Test
    fun `ResumeReminder with non-positive remaining is a no-op`() {
        val ringing = ringingState(NOW, ringingStartedAt = NOW)
        val r = TimerEngine.process(
            TimerEvent.ResumeReminder(NOW + 60_000L, ringing.phase, 0L),
            ringing,
            NOW + 60_000L
        )
        assertEquals(ringing, r.newState)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun `ResumeReminder in non-RINGING state is a no-op`() {
        val running = runningState()
        val r = TimerEngine.process(
            TimerEvent.ResumeReminder(NOW, running.phase, 15_000L),
            running,
            NOW
        )
        assertEquals(running, r.newState)
        assertTrue(r.effects.isEmpty())
    }

    // ===== 第三轮 P1 + P2.1 RingingRecovered 事件 =====

    @Test
    fun `RingingRecovered within 30s emits StartReminder with remaining time`() {
        // 首次提醒期内 → 补首次提醒剩余时长
        val ringing = ringingState(NOW, ringingStartedAt = NOW)
        val r = TimerEngine.process(
            TimerEvent.RingingRecovered(NOW + 10_000L), ringing, NOW + 10_000L
        )
        assertEquals(ringing, r.newState)
        val sr = r.effects.filterIsInstance<TimerEffect.StartReminder>().firstOrNull()
        assertNotNull(sr)
        assertEquals(20_000L, sr!!.durationMs)
    }

    @Test
    fun `RingingRecovered after repeat already fired is a no-op`() {
        val ringing = ringingState(
            NOW, ringingStartedAt = NOW - 200_000L,
            awaitingRepeatSince = NOW - 1_000L,
            repeatReminderFired = true
        )
        val r = TimerEngine.process(
            TimerEvent.RingingRecovered(NOW), ringing, NOW
        )
        assertEquals(ringing, r.newState)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun `RingingRecovered with explicit awaiting window is a no-op`() {
        // 已存在明确的 awaitingRepeatSinceEpochMillis → 等 OnTick 自然推进
        val ringing = ringingState(
            NOW, ringingStartedAt = NOW - 60_000L,
            awaitingRepeatSince = NOW - 30_000L
        )
        val r = TimerEngine.process(
            TimerEvent.RingingRecovered(NOW), ringing, NOW
        )
        assertEquals(ringing, r.newState)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun `RingingRecovered in abnormal path anchors awaiting to startedAt plus 30s without firing reminder`() {
        // v0.2 第三轮 P2.1 修复：异常恢复路径（awaiting == null 且 30s 已过但 3min 窗口未到）
        // 不再立刻补 15s 重复；而是把 awaiting 锚到 ringingStartedAt + 30s 作为隐含起点。
        // 等待窗口起点 = NOW + 30s（startedAt + 30s），不是 NOW。
        val ringing = ringingState(NOW, ringingStartedAt = NOW)  // startedAt = NOW
        val recoverAt = NOW + 45_000L  // 首次结束后 15s 恢复（仍在 3min 窗口内）
        val r = TimerEngine.process(
            TimerEvent.RingingRecovered(recoverAt), ringing, recoverAt
        )
        val state = r.newState!!
        // 关键：awaitingRepeatSinceEpochMillis = startedAt + 30s（不是 now）
        assertEquals(NOW + TimerEngine.REPEAT_WAIT_AFTER_AUTO_STOP_MS, state.awaitingRepeatSinceEpochMillis)
        // 关键：不发 StartReminder，等 OnTick 自然在 3min 窗口结束时触发
        assertEquals(0, r.effects.count { it is TimerEffect.StartReminder })
        // 持久化新 awaiting
        assertTrue(r.effects.any { it is TimerEffect.SaveRuntime })
        assertFalse(state.repeatReminderFired)
    }

    @Test
    fun `RingingRecovered after 3 min window emits 15s repeat and sets repeatReminderFired`() {
        // 异常恢复路径且隐含窗口已过 → 一次性补 15s 重复 + 标记 fired，避免 OnTick 再次触发
        val ringing = ringingState(NOW, ringingStartedAt = NOW)
        val recoverAt = NOW + TimerEngine.REPEAT_WAIT_AFTER_AUTO_STOP_MS +
            TimerEngine.REPEAT_WINDOW_MS + 5_000L  // 窗口过后 5s 恢复
        val r = TimerEngine.process(
            TimerEvent.RingingRecovered(recoverAt), ringing, recoverAt
        )
        val state = r.newState!!
        assertTrue(state.repeatReminderFired)
        assertNull(state.awaitingRepeatSinceEpochMillis)
        val sr = r.effects.filterIsInstance<TimerEffect.StartReminder>().firstOrNull()
        assertNotNull(sr)
        assertEquals(TimerEngine.REPEAT_REMINDER_DURATION_MS, sr!!.durationMs)
        assertTrue(r.effects.any { it is TimerEffect.SaveRuntime })
    }

    @Test
    fun `RingingRecovered prevents double repeat - OnTick after recovery does not fire again`() {
        // v0.2 第三轮 P1 核心回归：恢复路径标记了 repeatReminderFired=true 后，
        // 后续 OnTick 不应再次触发重复提醒。
        val ringing = ringingState(NOW, ringingStartedAt = NOW)
        val recoverAt = NOW + TimerEngine.REPEAT_WAIT_AFTER_AUTO_STOP_MS +
            TimerEngine.REPEAT_WINDOW_MS + 5_000L
        val recovered = TimerEngine.process(
            TimerEvent.RingingRecovered(recoverAt), ringing, recoverAt
        ).newState!!

        // 模拟 Service 后续 OnTick（任何时间点）
        val tickLater = recoverAt + 60_000L
        val tickResult = TimerEngine.process(
            TimerEvent.OnTick(tickLater), recovered, tickLater
        )
        // 关键：不再发第二次 StartReminder
        assertEquals(0, tickResult.effects.count { it is TimerEffect.StartReminder })
    }

    @Test
    fun `RingingRecovered in non-RINGING state is a no-op`() {
        val running = runningState()
        val r = TimerEngine.process(
            TimerEvent.RingingRecovered(NOW), running, NOW
        )
        assertEquals(running, r.newState)
        assertTrue(r.effects.isEmpty())
    }

    // ===== 第三轮 P2.2 FinishEarly 服务保证 =====

    @Test
    fun `FinishEarly emits StartForegroundService before StartReminder`() {
        // v0.2 第三轮 P2.2 修复：与自然到点路径保持一致
        val started = TimerEngine.process(
            TimerEvent.Start(NOW, TimerPhase.FOCUS, FOCUS_MS), null, NOW
        ).newState!!
        val r = TimerEngine.process(
            TimerEvent.FinishEarly(NOW + 60_000L), started, NOW + 60_000L
        )
        // 必须发 StartForegroundService
        assertTrue(r.effects.any { it is TimerEffect.StartForegroundService })
        // 顺序：StartForegroundService 在 StartReminder 之前
        val sfgIdx = r.effects.indexOfFirst { it is TimerEffect.StartForegroundService }
        val srIdx = r.effects.indexOfFirst { it is TimerEffect.StartReminder }
        assertTrue(sfgIdx in 0 until srIdx)
    }

    // ===== 辅助：构造 running / ringing 状态 =====

    private fun runningState(
        cyclePos: Int = 0,
        cycles: Int = 3,
        focusMin: Int = 25,
        shortMin: Int = 5,
        longMin: Int = 15,
        phase: TimerPhase = TimerPhase.FOCUS
    ): TimerRuntimeState = TimerRuntimeState(
        sessionId = 1L,
        phase = phase,
        runState = TimerRunState.RUNNING,
        plannedDurationMillis = when (phase) {
            TimerPhase.FOCUS -> focusMin * 60_000L
            TimerPhase.SHORT_BREAK -> shortMin * 60_000L
            TimerPhase.LONG_BREAK -> longMin * 60_000L
        },
        startedAtEpochMillis = NOW,
        targetEndAtEpochMillis = NOW + 60_000L,
        pausedAtEpochMillis = null,
        accumulatedPausedMillis = 0L,
        extensionCount = 0,
        sessionCompletionRecorded = false,
        cyclePositionAtStart = cyclePos,
        longBreakMinutesAtStart = longMin,
        shortBreakMinutesAtStart = shortMin,
        focusMinutesAtStart = focusMin,
        cyclesBeforeLongBreakAtStart = cycles
    )

    private fun ringingState(
        @Suppress("UNUSED_PARAMETER") baseNow: Long,
        ringingStartedAt: Long,
        awaitingRepeatSince: Long? = null,
        repeatReminderFired: Boolean = false,
        phase: TimerPhase = TimerPhase.FOCUS,
        cyclePos: Int = 0,
        cycles: Int = 3
    ): TimerRuntimeState = runningState(
        cyclePos = cyclePos, cycles = cycles, phase = phase
    ).copy(
        runState = TimerRunState.RINGING,
        ringingStartedAtEpochMillis = ringingStartedAt,
        awaitingRepeatSinceEpochMillis = awaitingRepeatSince,
        repeatReminderFired = repeatReminderFired
    )
}
