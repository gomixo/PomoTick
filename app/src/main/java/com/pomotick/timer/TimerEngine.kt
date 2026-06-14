package com.pomotick.timer

import com.pomotick.data.SessionStatus
import com.pomotick.data.TimerSession
import kotlin.random.Random

/**
 * **纯函数式状态机**。不直接 IO，不写库，不启动 Service，不调 ReminderManager。
 *
 * 输入：[TimerEvent] + 当前 [TimerRuntimeState] + `now`，输出 [TimerEngineResult]（新状态 + 副作用意图）。
 *
 * 调用方负责按需执行 effects；执行顺序由调用方控制。
 *
 * ## sessionCompletionRecorded 字段语义
 * - `false`（默认）：当前 session 尚未写入历史表
 * - `true`：[TimerEvent.FinishEarly] 已立即写入；后续 [TimerEvent.Respond] 不再重复写
 *
 * 用途：保证"提前结束"路径和"到点自然完成 + 用户响应"路径**总共**只写一条 session 记录。
 */
object TimerEngine {

    /**
     * 处理事件，产出新状态 + 副作用列表。
     */
    fun process(
        event: TimerEvent,
        current: TimerRuntimeState?,
        now: Long
    ): TimerEngineResult = when (event) {
        is TimerEvent.Start -> handleStart(event, now)
        is TimerEvent.OnTick -> handleTick(current, now)
        is TimerEvent.Pause -> handlePause(current, event.now)
        is TimerEvent.Resume -> handleResume(current, event.now)
        is TimerEvent.Extend -> handleExtend(current, event)
        is TimerEvent.FinishEarly -> handleFinishEarly(current, event.now)
        is TimerEvent.Abandon -> handleAbandon(current, event.now)
        is TimerEvent.Respond -> handleRespond(current, event)
    }

    /**
     * 计算下一个 phase（用于 [TimerEvent.Respond.StartBreak]）。
     *
     * 规则：每完成 4 个 FOCUS → 下一个是 LONG_BREAK，否则 SHORT_BREAK。
     * 内部调用方需传入最近完成 session 列表（按 endedAtEpochMillis 倒序）。
     */
    fun nextPhase(history: List<TimerSession>): TimerPhase {
        val recentFocusCompleted = history.asSequence()
            .filter { it.status == SessionStatus.COMPLETED && it.phase == TimerPhase.FOCUS }
            .take(4)
            .toList()
        return when {
            recentFocusCompleted.size < 4 -> TimerPhase.SHORT_BREAK
            recentFocusCompleted.size == 4 -> TimerPhase.LONG_BREAK
            else -> TimerPhase.SHORT_BREAK
        }
    }

    // ===== 事件处理器 =====

    private fun handleStart(event: TimerEvent.Start, now: Long): TimerEngineResult {
        val state = TimerRuntimeState(
            sessionId = generateSessionId(),
            phase = event.phase,
            runState = TimerRunState.RUNNING,
            plannedDurationMillis = event.plannedMs,
            startedAtEpochMillis = now,
            targetEndAtEpochMillis = now + event.plannedMs,
            pausedAtEpochMillis = null,
            accumulatedPausedMillis = 0L,
            extensionCount = 0,
            sessionCompletionRecorded = false
        )
        return TimerEngineResult.of(
            state,
            listOf(
                TimerEffect.SaveRuntime(state),
                TimerEffect.StartForegroundService,
                TimerEffect.UpdateNotification(event.plannedMs, event.phase)
            )
        )
    }

    private fun handleTick(current: TimerRuntimeState?, now: Long): TimerEngineResult {
        if (current == null) return TimerEngineResult.idle()

        return when (current.runState) {
            TimerRunState.RUNNING -> {
                if (now >= current.targetEndAtEpochMillis) {
                    // 到点 → RINGING，发出 StartReminder 让调用方触发震动
                    val ringing = current.copy(runState = TimerRunState.RINGING)
                    TimerEngineResult.of(
                        ringing,
                        listOf(
                            TimerEffect.SaveRuntime(ringing),
                            TimerEffect.StartReminder(ringing.phase, /* strength handled by caller */ -1),
                            TimerEffect.UpdateNotification(0L, ringing.phase)
                        )
                    )
                } else {
                    val remaining = (current.targetEndAtEpochMillis - now).coerceAtLeast(0L)
                    TimerEngineResult.of(
                        current,
                        listOf(TimerEffect.UpdateNotification(remaining, current.phase))
                    )
                }
            }
            TimerRunState.PAUSED -> {
                val remaining = remainingMillis(now, current)
                TimerEngineResult.of(
                    current,
                    listOf(TimerEffect.UpdateNotification(remaining, current.phase))
                )
            }
            TimerRunState.RINGING -> {
                // 已在 RINGING，tick 仅更新通知
                TimerEngineResult.of(
                    current,
                    listOf(TimerEffect.UpdateNotification(0L, current.phase))
                )
            }
            TimerRunState.IDLE, TimerRunState.FINISHED -> TimerEngineResult.idle()
        }
    }

    private fun handlePause(current: TimerRuntimeState?, now: Long): TimerEngineResult {
        val state = current ?: return TimerEngineResult.idle()
        if (state.runState != TimerRunState.RUNNING) {
            return TimerEngineResult.of(state, emptyList())
        }
        val paused = state.copy(
            runState = TimerRunState.PAUSED,
            pausedAtEpochMillis = now
        )
        return TimerEngineResult.of(
            paused,
            listOf(
                TimerEffect.SaveRuntime(paused),
                TimerEffect.UpdateNotification(remainingMillis(now, paused), paused.phase)
            )
        )
    }

    private fun handleResume(current: TimerRuntimeState?, now: Long): TimerEngineResult {
        val state = current ?: return TimerEngineResult.idle()
        if (state.runState != TimerRunState.PAUSED || state.pausedAtEpochMillis == null) {
            return TimerEngineResult.of(state, emptyList())
        }
        val pauseAt = state.pausedAtEpochMillis
        val pauseDuration = now - pauseAt
        val resumed = state.copy(
            runState = TimerRunState.RUNNING,
            pausedAtEpochMillis = null,
            accumulatedPausedMillis = state.accumulatedPausedMillis + pauseDuration,
            targetEndAtEpochMillis = state.targetEndAtEpochMillis + pauseDuration
        )
        return TimerEngineResult.of(
            resumed,
            listOf(
                TimerEffect.SaveRuntime(resumed),
                TimerEffect.UpdateNotification(remainingMillis(now, resumed), resumed.phase)
            )
        )
    }

    private fun handleExtend(current: TimerRuntimeState?, event: TimerEvent.Extend): TimerEngineResult {
        val state = current ?: return TimerEngineResult.idle()
        if (state.runState !in listOf(TimerRunState.RUNNING, TimerRunState.PAUSED, TimerRunState.RINGING)) {
            return TimerEngineResult.of(state, emptyList())
        }
        val extended = state.copy(
            targetEndAtEpochMillis = state.targetEndAtEpochMillis + event.deltaMs,
            extensionCount = state.extensionCount + 1,
            runState = TimerRunState.RUNNING,
            pausedAtEpochMillis = null
        )
        return TimerEngineResult.of(
            extended,
            listOf(
                TimerEffect.SaveRuntime(extended),
                TimerEffect.StopReminder,
                TimerEffect.UpdateNotification(remainingMillis(event.now, extended), extended.phase)
            )
        )
    }

    /**
     * 提前结束：立即构造 COMPLETED session 并置 `sessionCompletionRecorded = true`，然后进入 RINGING。
     *
     * 关键：调用方执行 effects 时必须先执行 RecordSession（先入库），再 SaveRuntime（切状态）。
     */
    private fun handleFinishEarly(current: TimerRuntimeState?, now: Long): TimerEngineResult {
        val state = current ?: return TimerEngineResult.idle()
        val actual = actualFocusMillis(now, state)
        val session = TimerEffect.buildSession(
            phase = state.phase,
            startedAtEpochMillis = state.startedAtEpochMillis,
            endedAtEpochMillis = now,
            plannedDurationMillis = state.plannedDurationMillis,
            actualFocusMillis = actual,
            status = SessionStatus.COMPLETED,
            extensionCount = state.extensionCount
        )
        val ringing = state.copy(
            runState = TimerRunState.RINGING,
            sessionCompletionRecorded = true   // 已写，不再重复
        )
        return TimerEngineResult.of(
            ringing,
            listOf(
                TimerEffect.RecordSession(session),     // 先入库
                TimerEffect.SaveRuntime(ringing),
                TimerEffect.StartReminder(ringing.phase, -1),
                TimerEffect.UpdateNotification(0L, ringing.phase)
            )
        )
    }

    private fun handleAbandon(current: TimerRuntimeState?, now: Long): TimerEngineResult {
        val state = current ?: return TimerEngineResult.idle()
        val actual = actualFocusMillis(now, state)
        val session = TimerEffect.buildSession(
            phase = state.phase,
            startedAtEpochMillis = state.startedAtEpochMillis,
            endedAtEpochMillis = now,
            plannedDurationMillis = state.plannedDurationMillis,
            actualFocusMillis = actual,
            status = SessionStatus.INTERRUPTED,
            extensionCount = state.extensionCount
        )
        return TimerEngineResult.idle(
            listOf(
                TimerEffect.RecordSession(session),
                TimerEffect.ClearRuntime,
                TimerEffect.StopForegroundService,
                TimerEffect.StopReminder
            )
        )
    }

    /**
     * RINGING 响应处理。
     *
     * 写入历史表的统一规则：
     * - 若 `current.sessionCompletionRecorded == true`（来自 FinishEarly）→ 不再写
     * - 若 `false`（自然到点）→ 写 COMPLETED session
     *
     * 这样保证"提前结束"和"到点 + 用户响应"两条路径**总共只写一条**。
     */
    private fun handleRespond(
        current: TimerRuntimeState?,
        event: TimerEvent.Respond
    ): TimerEngineResult {
        val state = current ?: return TimerEngineResult.idle()
        if (state.runState != TimerRunState.RINGING) {
            return TimerEngineResult.of(state, emptyList())
        }
        return when (event.action) {
            ResponseAction.KnowIt -> {
                val effects = mutableListOf<TimerEffect>()
                if (!state.sessionCompletionRecorded) {
                    effects += TimerEffect.RecordSession(buildCompletionSession(state, event.now))
                }
                effects += TimerEffect.ClearRuntime
                effects += TimerEffect.StopForegroundService
                effects += TimerEffect.StopReminder
                TimerEngineResult.idle(effects)
            }
            ResponseAction.StartBreak -> {
                // 调用方（ViewModel/Repository）需在调用前确定 nextPhase + plannedMs（基于 history 与 settings），
                // 并通过 event.options 传入。Engine 严格按输入执行。
                val phase = event.options.nextPhase ?: TimerPhase.SHORT_BREAK
                val plannedMs = event.options.plannedMs ?: 5L * 60L * 1000L
                val breakState = TimerRuntimeState(
                    sessionId = generateSessionId(),
                    phase = phase,
                    runState = TimerRunState.RUNNING,
                    plannedDurationMillis = plannedMs,
                    startedAtEpochMillis = event.now,
                    targetEndAtEpochMillis = event.now + plannedMs,
                    pausedAtEpochMillis = null,
                    accumulatedPausedMillis = 0L,
                    extensionCount = 0,
                    sessionCompletionRecorded = false
                )
                val effects = mutableListOf<TimerEffect>()
                if (!state.sessionCompletionRecorded) {
                    effects += TimerEffect.RecordSession(buildCompletionSession(state, event.now))
                }
                effects += TimerEffect.SaveRuntime(breakState)
                effects += TimerEffect.StopReminder
                effects += TimerEffect.UpdateNotification(breakState.plannedDurationMillis, breakState.phase)
                TimerEngineResult.of(breakState, effects)
            }
            ResponseAction.ContinueFocus -> handleExtend(state, TimerEvent.Extend(event.now))
        }
    }

    private fun buildCompletionSession(current: TimerRuntimeState, endedAt: Long): TimerSession =
        TimerEffect.buildSession(
            phase = current.phase,
            startedAtEpochMillis = current.startedAtEpochMillis,
            endedAtEpochMillis = endedAt,
            plannedDurationMillis = current.plannedDurationMillis,
            actualFocusMillis = actualFocusMillis(endedAt, current),
            status = SessionStatus.COMPLETED,
            extensionCount = current.extensionCount
        )

    private fun generateSessionId(): Long =
        Random.nextLong().let { if (it < 0) -it else it }
}
