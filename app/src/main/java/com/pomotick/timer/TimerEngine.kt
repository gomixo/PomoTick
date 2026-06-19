package com.pomotick.timer

import com.pomotick.data.SessionStatus
import com.pomotick.data.TimerSession
import kotlin.random.Random

/**
 * **纯函数式状态机**——所有决策基于事件 + 当前状态，不依赖任何 IO / Clock。
 *
 * ## v0.2 关键变化
 *
 * 1. **完成阶段 → 下一阶段决策**（`computeNextPhase` + `durationFor`）：
 *    不再由 ViewModel 计算后通过 RespondOptions 传入；Engine 直接使用
 *    [TimerRuntimeState] 的"开始时配置快照"（`focusMinutesAtStart` /
 *    `shortBreakMinutesAtStart` / `longBreakMinutesAtStart` /
 *    `cyclesBeforeLongBreakAtStart` / `cyclePositionAtStart`）计算。
 *    这样做的好处：通知 Action（绕开 ViewModel）和 UI 按钮走**完全相同**的
 *    决策路径，轮次位置、休息类型不会因为入口不同而错乱。
 *
 * 2. **轮次推进下放至 Engine**：[TimerEffect.AdvanceCycle] 在
 *    `handleStopRingingAndPrepareNext` / `handleFinishEarly` / `handleRespond`
 *    写出 session 时一同发出，由 [com.pomotick.data.TimerRepository] 翻译为
 *    `settingsStore.setCyclePosition(...)`。`TimerViewModel` 不再手动维护
 *    轮次位置。
 *
 * 3. **StartReminder 携带 `durationMs`**：Service 不再反查 runtime
 *    `repeatReminderFired` 来决定 15s/30s——由 Engine 在发送 effect 时就
 *    算好，避免"effect 到达时 runtime 还没更新"的竞态。
 *
 * 4. **RUNNING → RINGING 必发 `StartForegroundService`**：冷启动时如果发现
 *    `now >= targetEnd`，先发 `StartForegroundService` 再发 `StartReminder`，
 *    Service 启动并 register 自身后再接收 `StartReminder`，避免 effect 丢失。
 *
 * 5. **§4 重复提醒调度**：`scheduleRepeatReminder` 在 RINGING tick 中检测
 *    30s 首次结束 + 3min 窗口 + 1 次 15s 重复，所有 §4 字段变更都伴随
 *    `SaveRuntime` effect，使重启后可恢复窗口。
 *
 * 6. **新增 `StopRingingOnly` 事件**：仅停声震、保持 RINGING、启动 §4 等待
 *    窗口；与 `StopRingingAndPrepareNext`（真正"完成 → 推进阶段"）严格区分。
 *    通知"停止"Action 走 `StopRingingOnly`；UI 才有"知道了"按钮走
 *    `StopRingingAndPrepareNext`。
 */
object TimerEngine {

    /**
     * 处理 [event] + [current] 状态 → 返回新状态 + 副作用列表。
     */
    fun process(event: TimerEvent, current: TimerRuntimeState?, now: Long): TimerEngineResult {
        return when (event) {
            is TimerEvent.Start -> handleStart(event, now)
            is TimerEvent.Pause -> handlePause(current, event, now)
            is TimerEvent.Resume -> handleResume(current, event, now)
            is TimerEvent.Extend -> handleExtend(current, event, now)
            is TimerEvent.FinishEarly -> handleFinishEarly(current, event, now)
            is TimerEvent.Abandon -> handleAbandon(current, now)
            is TimerEvent.OnTick -> handleTick(current, now)
            is TimerEvent.Respond -> handleRespond(event, current, now)
            is TimerEvent.StopRingingAndPrepareNext -> handleStopRingingAndPrepareNext(current, now)
            is TimerEvent.StopRingingOnly -> handleStopRingingOnly(current, now)
            is TimerEvent.SwitchPhase -> handleSwitchPhase(event)
            is TimerEvent.Reset -> handleReset(event)
            is TimerEvent.ResumeReminder -> handleResumeReminder(event, current)
            is TimerEvent.RingingRecovered -> handleRingingRecovered(current, now)
        }
    }

    // ===== 启动 =====

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
            sessionCompletionRecorded = false,
            // v0.2 §9: 写入开始时配置快照——中途改设置不影响当前 session
            cyclePositionAtStart = event.cyclePositionAtStart,
            longBreakMinutesAtStart = event.longBreakMinutesAtStart,
            shortBreakMinutesAtStart = event.shortBreakMinutesAtStart,
            focusMinutesAtStart = event.focusMinutesAtStart,
            cyclesBeforeLongBreakAtStart = event.cyclesBeforeLongBreakAtStart,
            // §4 字段在新建 session 时重置（不应有残留）
            ringingStartedAtEpochMillis = null,
            awaitingRepeatSinceEpochMillis = null,
            repeatReminderFired = false
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

    // ===== 暂停 / 恢复 / 延长 =====

    private fun handlePause(current: TimerRuntimeState?, event: TimerEvent.Pause, now: Long): TimerEngineResult {
        val state = current ?: return TimerEngineResult.idle()
        // v0.2 P2.1 修复：非法事件（Pause 在非 RUNNING 状态）保持当前 state + empty effects，
        // 而不是返回 idle() 把内存 runtime 置空。Repository 收到 idle() 会把 _currentRuntime
        // 设为 null，但 DataStore 里其实还有持久化数据，会出现 UI 闪到 IDLE 的瞬态。
        if (state.runState != TimerRunState.RUNNING) return TimerEngineResult.of(state, emptyList())
        val paused = state.copy(
            runState = TimerRunState.PAUSED,
            pausedAtEpochMillis = now
        )
        return TimerEngineResult.of(
            paused,
            listOf(
                TimerEffect.SaveRuntime(paused),
                TimerEffect.UpdateNotification(remainingMillis(now, state), state.phase)
            )
        )
    }

    private fun handleResume(current: TimerRuntimeState?, event: TimerEvent.Resume, now: Long): TimerEngineResult {
        val state = current ?: return TimerEngineResult.idle()
        if (state.runState != TimerRunState.PAUSED) return TimerEngineResult.of(state, emptyList())
        val pauseDuration = now - (state.pausedAtEpochMillis ?: now)
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

    private fun handleExtend(current: TimerRuntimeState?, event: TimerEvent.Extend, now: Long): TimerEngineResult {
        val state = current ?: return TimerEngineResult.idle()
        if (state.runState != TimerRunState.RUNNING) return TimerEngineResult.of(state, emptyList())
        val extended = state.copy(
            plannedDurationMillis = state.plannedDurationMillis + event.extraMs,
            targetEndAtEpochMillis = state.targetEndAtEpochMillis + event.extraMs,
            extensionCount = state.extensionCount + 1
        )
        return TimerEngineResult.of(
            extended,
            listOf(
                TimerEffect.SaveRuntime(extended),
                TimerEffect.UpdateNotification(remainingMillis(now, extended), extended.phase)
            )
        )
    }

    private fun handleAbandon(current: TimerRuntimeState?, now: Long): TimerEngineResult {
        val state = current ?: return TimerEngineResult.idle()
        val abandoned = state.copy(
            runState = TimerRunState.IDLE,
            pausedAtEpochMillis = null
        )
        val session = TimerEffect.buildSession(
            phase = state.phase,
            startedAtEpochMillis = state.startedAtEpochMillis,
            endedAtEpochMillis = now,
            plannedDurationMillis = state.plannedDurationMillis,
            actualFocusMillis = actualFocusMillis(now, state),
            status = SessionStatus.INTERRUPTED,
            extensionCount = state.extensionCount
        )
        return TimerEngineResult.idle(
            listOf(
                TimerEffect.RecordSession(session),
                TimerEffect.SaveRuntime(abandoned),
                TimerEffect.StopReminder,
                TimerEffect.ClearRuntime,
                TimerEffect.StopForegroundService
            )
        )
    }

    // ===== 完成（自然到点） =====

    private fun handleTick(current: TimerRuntimeState?, now: Long): TimerEngineResult {
        if (current == null) return TimerEngineResult.idle()

        return when (current.runState) {
            TimerRunState.RUNNING -> {
                if (now >= current.targetEndAtEpochMillis) {
                    val ringing = enterRinging(current, now)
                    TimerEngineResult.of(
                        ringing,
                        listOf(
                            TimerEffect.SaveRuntime(ringing),
                            // 冷启动恢复时也要先启动 Service，否则 effect 会丢失
                            TimerEffect.StartForegroundService,
                            TimerEffect.StartReminder(ringing.phase, FIRST_REMINDER_DURATION_MS),
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
                // §4 重复提醒调度
                val tickInRinging = scheduleRepeatReminder(current, now)
                TimerEngineResult.of(
                    tickInRinging.state,
                    tickInRinging.effects
                )
            }
            TimerRunState.IDLE, TimerRunState.FINISHED -> TimerEngineResult.idle()
        }
    }

    /**
     * 构造"进入 RINGING"的新状态，重置整组 §4 字段。
     */
    private fun enterRinging(current: TimerRuntimeState, now: Long): TimerRuntimeState =
        current.copy(
            runState = TimerRunState.RINGING,
            ringingStartedAtEpochMillis = now,
            awaitingRepeatSinceEpochMillis = null,
            repeatReminderFired = false
        )

    /**
     * §4: 在 RINGING tick 中按时间戳检查是否需要：
     *
     * 1. 启动 3 分钟等待窗口（首次提醒 30s 自动停止 / 用户手动停止时）
     * 2. 触发 1 次 15s 重复提醒（窗口到期时）
     *
     * **关键：每次状态变更都伴随 [TimerEffect.SaveRuntime]**，保证 App 重启后能
     * 从持久化数据中接续等待窗口。
     */
    private fun scheduleRepeatReminder(
        current: TimerRuntimeState,
        now: Long
    ): TickInRingingResult {
        val startedAt = current.ringingStartedAtEpochMillis ?: return tickOnly(current)
        val elapsedSinceRingStart = now - startedAt

        if (current.awaitingRepeatSinceEpochMillis == null &&
            elapsedSinceRingStart >= REPEAT_WAIT_AFTER_AUTO_STOP_MS
        ) {
            // 首次提醒已结束 → 启动 3 分钟等待窗口
            val updated = current.copy(awaitingRepeatSinceEpochMillis = now)
            return TickInRingingResult(
                updated,
                listOf(
                    TimerEffect.SaveRuntime(updated),
                    TimerEffect.UpdateNotification(0L, updated.phase)
                )
            )
        }

        if (current.awaitingRepeatSinceEpochMillis != null &&
            !current.repeatReminderFired &&
            now - current.awaitingRepeatSinceEpochMillis >= REPEAT_WINDOW_MS
        ) {
            // 3 分钟窗口到期 → 触发 1 次 15s 重复提醒
            val updated = current.copy(
                repeatReminderFired = true,
                awaitingRepeatSinceEpochMillis = null
            )
            return TickInRingingResult(
                updated,
                listOf(
                    TimerEffect.SaveRuntime(updated),
                    TimerEffect.StartReminder(updated.phase, REPEAT_REMINDER_DURATION_MS),
                    TimerEffect.UpdateNotification(0L, updated.phase)
                )
            )
        }

        return tickOnly(current)
    }

    private fun tickOnly(current: TimerRuntimeState): TickInRingingResult =
        TickInRingingResult(
            current,
            listOf(TimerEffect.UpdateNotification(0L, current.phase))
        )

    private data class TickInRingingResult(
        val state: TimerRuntimeState,
        val effects: List<TimerEffect>
    )

    // ===== 完成（手动提前结束） =====

    private fun handleFinishEarly(current: TimerRuntimeState?, event: TimerEvent.FinishEarly, now: Long): TimerEngineResult {
        val state = current ?: return TimerEngineResult.idle()
        // FinishEarly 仅在 RUNNING / PAUSED 时有意义；其他状态（含 IDLE）无效
        if (state.runState != TimerRunState.RUNNING && state.runState != TimerRunState.PAUSED) {
            return TimerEngineResult.of(state, emptyList())
        }
        val actual = actualFocusMillis(now, state)
        val session = TimerEffect.buildSession(
            phase = state.phase,
            startedAtEpochMillis = state.startedAtEpochMillis,
            endedAtEpochMillis = now,
            plannedDurationMillis = state.plannedDurationMillis,
            actualFocusMillis = actual,
            // v0.2 P2: 区分自然完成 / 提前结束
            status = SessionStatus.EARLY_FINISHED,
            extensionCount = state.extensionCount
        )
        val ringing = enterRinging(state, now).copy(
            sessionCompletionRecorded = true
        )
        return TimerEngineResult.of(
            ringing,
            // v0.2 第三轮 P2.2 修复：FinishEarly 也发 StartForegroundService，
            // 与自然到点路径保持一致。正常 RUNNING 时服务应存在；但如果服务被系统杀掉、
            // UI 仍在，用户点提前结束时仍要拉起服务，否则 StartReminder 会因 Service
            // 未注册而丢失（仅靠 awaitServiceForEffect 的 1.5s 等待太脆弱）。
            listOf(
                TimerEffect.RecordSession(session),
                TimerEffect.SaveRuntime(ringing),
                TimerEffect.AdvanceCycle(state.phase),  // 提前结束也推进轮次
                TimerEffect.StartForegroundService,
                TimerEffect.StartReminder(ringing.phase, FIRST_REMINDER_DURATION_MS),
                TimerEffect.UpdateNotification(0L, ringing.phase)
            )
        )
    }

    // ===== 完成（用户响应） =====

    private fun handleRespond(
        event: TimerEvent.Respond,
        current: TimerRuntimeState?,
        now: Long
    ): TimerEngineResult {
        val state = current ?: return TimerEngineResult.idle()
        if (state.runState != TimerRunState.RINGING) return TimerEngineResult.of(state, emptyList())

        return when (event.action) {
            ResponseAction.KnowIt -> {
                // 只停提醒、不切换阶段
                handleStopRingingOnly(state, now)
            }
            ResponseAction.StartBreak -> {
                // 用户主动选择进入下一休息阶段
                handleCompletionFlow(state, now, advanceToNext = true)
            }
            ResponseAction.ContinueFocus -> {
                // v0.2 P2.2 修复：只延长 5 分钟（追加到 targetEnd），不要重新加 plannedDurationMillis。
                // 旧实现 `now + state.plannedDurationMillis + EXTEND_FOCUS_MS` 在 25 分钟到点
                // 后再点会变成 ~30 分钟，违反"延长 5 分钟"语义。
                val extended = state.copy(
                    runState = TimerRunState.RUNNING,
                    plannedDurationMillis = state.plannedDurationMillis + EXTEND_FOCUS_MS,
                    targetEndAtEpochMillis = state.targetEndAtEpochMillis + EXTEND_FOCUS_MS,
                    pausedAtEpochMillis = null,
                    extensionCount = state.extensionCount + 1,
                    ringingStartedAtEpochMillis = null,
                    awaitingRepeatSinceEpochMillis = null,
                    repeatReminderFired = false,
                    sessionCompletionRecorded = false
                )
                TimerEngineResult.of(
                    extended,
                    listOf(
                        TimerEffect.SaveRuntime(extended),
                        TimerEffect.StopReminder,
                        TimerEffect.UpdateNotification(state.targetEndAtEpochMillis + EXTEND_FOCUS_MS - now, extended.phase)
                    )
                )
            }
        }
    }

    // ===== 停止响铃（停声震，保持 RINGING） =====

    /**
     * v0.2 P1 修复：仅停声震，保持 RINGING + 启动 §4 等待窗口。
     *
     * - 不修改 `runState`（保持 RINGING）
     * - 不写 session（用户尚未"完成"）
     * - 不发 `ClearRuntime` / `StopForegroundService`（服务继续运行）
     * - 发 `StopReminder`（让 Service 停声震）
     * - 发 `SaveRuntime`（持久化 awaitingRepeatSinceEpochMillis / 30s 等待窗口）
     *
     * 这是通知"停止"Action 的入口。UI 上对应"停止声震"按钮。
     */
    private fun handleStopRingingOnly(current: TimerRuntimeState?, now: Long): TimerEngineResult {
        val state = current ?: return TimerEngineResult.idle()
        if (state.runState != TimerRunState.RINGING) return TimerEngineResult.of(state, emptyList())
        val effects = mutableListOf<TimerEffect>(TimerEffect.StopReminder)
        var updated = state

        // v0.2 P1 修复：手动停止也立即启动 3 分钟等待窗口。
        // 需求："第一次提醒结束（包括用户手动停止提醒）后 3 分钟无新动作触发重复提醒"。
        // 旧实现：30s 内手动停止时不设置 awaitingRepeatSinceEpochMillis，而是继续等原 30s 阈值——
        // 比如第 5 秒停止时，重复提醒会在 3 分 30 秒后才触发，违反"3 分钟无动作"的语义。
        // 新实现：手动停止就把"等待窗口起点"锚到 now，3 分钟后触发。
        if (updated.awaitingRepeatSinceEpochMillis == null && !updated.repeatReminderFired) {
            updated = updated.copy(awaitingRepeatSinceEpochMillis = now)
        }
        // 持久化新的 awaitingRepeatSinceEpochMillis，重启后可恢复窗口
        effects += TimerEffect.SaveRuntime(updated)
        effects += TimerEffect.UpdateNotification(0L, updated.phase)
        return TimerEngineResult.of(updated, effects)
    }

    // ===== 停止响铃 + 推进下一阶段（"知道了"） =====

    /**
     * v0.2 P1 修复：通知/UI"知道了"的真正入口——停声震 + 写 session + 推进轮次 + 进入下一阶段。
     *
     * - 写 COMPLETED session
     * - 发 [TimerEffect.AdvanceCycle] 由 Repository 翻译为 settingsStore 更新
     * - 计算下一阶段 + plannedMs（基于 runtime 快照，不是 ViewModel）
     * - 发 [TimerEffect.SaveSelectedPhase] 更新 settings.selectedPhase
     * - 清 runtime + 停服务
     */
    private fun handleStopRingingAndPrepareNext(current: TimerRuntimeState?, now: Long): TimerEngineResult {
        val state = current ?: return TimerEngineResult.idle()
        if (state.runState != TimerRunState.RINGING) return TimerEngineResult.of(state, emptyList())
        return handleCompletionFlow(state, now, advanceToNext = true)
    }

    private fun handleCompletionFlow(
        state: TimerRuntimeState,
        now: Long,
        advanceToNext: Boolean
    ): TimerEngineResult {
        val completedPhase = state.phase

        // v0.2 P0 修复：若 FinishEarly 已经写过 session (sessionCompletionRecorded=true)
        // 这里就不能再写一条 COMPLETED 并再次 AdvanceCycle，否则统计/轮次会重复。
        // 行为：仅当"当前流程"才是真正的"自然完成 → 写 COMPLETED session → 推进轮次"，
        // 否则只执行 StopReminder，让 UI 显式进入下一阶段时再走对应的 Start 事件。
        val alreadyRecorded = state.sessionCompletionRecorded

        val effects = mutableListOf<TimerEffect>()

        if (!alreadyRecorded) {
            val actual = actualFocusMillis(now, state)
            val session = TimerEffect.buildSession(
                phase = completedPhase,
                startedAtEpochMillis = state.startedAtEpochMillis,
                endedAtEpochMillis = now,
                plannedDurationMillis = state.plannedDurationMillis,
                actualFocusMillis = actual,
                status = SessionStatus.COMPLETED,
                extensionCount = state.extensionCount
            )
            effects += TimerEffect.RecordSession(session)
            effects += TimerEffect.AdvanceCycle(completedPhase)
            effects += TimerEffect.SaveRuntime(state.copy(sessionCompletionRecorded = true))
        }
        effects += TimerEffect.StopReminder

        return if (advanceToNext) {
            val nextPhase = computeNextPhase(state, completedPhase)
            effects += TimerEffect.SaveSelectedPhase(nextPhase)
            effects += TimerEffect.ClearRuntime
            effects += TimerEffect.StopForegroundService
            // 新 runtime = null，selectedPhase 由 settings 决定
            TimerEngineResult.idle(effects)
        } else {
            // KnowIt 路径（不切换阶段）——保留当前 runtime 让 UI 显式选择
            val nextState = if (alreadyRecorded) state else state.copy(sessionCompletionRecorded = true)
            TimerEngineResult.of(nextState, effects)
        }
    }

    // ===== 切换阶段 / 重置 =====

    private fun handleSwitchPhase(event: TimerEvent.SwitchPhase): TimerEngineResult {
        return TimerEngineResult.idle(listOf(TimerEffect.SaveSelectedPhase(event.phase)))
    }

    private fun handleReset(event: TimerEvent.Reset): TimerEngineResult {
        val effects = mutableListOf<TimerEffect>(
            TimerEffect.StopReminder,
            TimerEffect.SaveSelectedPhase(event.selectedPhase),
            TimerEffect.ClearRuntime,
            TimerEffect.StopForegroundService
        )
        return TimerEngineResult.idle(effects)
    }

    // ===== 冷启动恢复（仅发 StartReminder，不修改 runtime） =====

    /**
     * v0.2 P1 修复：App/Service 重启时，ViewModel 已在 onAppStart 按时间戳计算
     * 出"还需响多久"，通过 [TimerEvent.ResumeReminder] 提交到这里。
     *
     * Engine 仅发出 [TimerEffect.StartReminder]，不修改 runtime 状态——
     * 持续时长由事件自带 `remainingMs` 决定（首次 30s 剩余 / 重复 15s 完整）。
     *
     * 必须以 RINGING 状态作为前置；否则不动作（保持当前 state）。
     */
    private fun handleResumeReminder(
        event: TimerEvent.ResumeReminder,
        current: TimerRuntimeState?
    ): TimerEngineResult {
        val state = current ?: return TimerEngineResult.idle()
        if (state.runState != TimerRunState.RINGING) return TimerEngineResult.of(state, emptyList())
        val remaining = event.remainingMs.coerceAtLeast(0L)
        if (remaining <= 0L) return TimerEngineResult.of(state, emptyList())
        return TimerEngineResult.of(
            state,
            listOf(TimerEffect.StartReminder(event.phase, remaining))
        )
    }

    /**
     * v0.2 第三轮 P1 修复：冷启动 RINGING 状态归一化。
     *
     * 旧实现的问题（[TimerViewModel.computeRingingResumeMs]）：
     * 1. 在 `awaitingRepeatSinceEpochMillis == null` 且首次 30s 已过的异常路径
     *    直接拼 `StartReminder(phase, 15s)`，但 **没设置 `repeatReminderFired = true`**。
     *    随后 Service 的 tick 走 `scheduleRepeatReminder` 看到 awaiting == null 会再
     *    设一遍 awaiting = now，3 分钟后再次触发重复提醒——重复触发。
     * 2. 异常恢复路径过早触发重复提醒：App 在首次响铃后 45 秒恢复时立刻补 15s，
     *    而需求是 `ringingStartedAt + 30s` 后再等 3 分钟。
     *
     * 新实现：把"按时间戳归一化"全部集中在这里：
     * - 首次 30s 期内 → 发 `StartReminder(phase, 30s - elapsed)` 补首次提醒
     * - 首次已结束 + 已 `repeatReminderFired` → 不动作
     * - 首次已结束 + 隐含窗口（`startedAt + 30s ~ +30s + 3min`）未到 →
     *   把 `awaitingRepeatSinceEpochMillis` 锚到 `startedAt + 30s`，**不补提醒**
     * - 隐含窗口已过 → 一次性发 15s 重复 + 标记 `repeatReminderFired = true`
     *
     * ViewModel 不再直接拼 StartReminder effect，也不再计算 remainingMs。
     */
    private fun handleRingingRecovered(
        current: TimerRuntimeState?,
        now: Long
    ): TimerEngineResult {
        val state = current ?: return TimerEngineResult.idle()
        if (state.runState != TimerRunState.RINGING) return TimerEngineResult.of(state, emptyList())
        val startedAt = state.ringingStartedAtEpochMillis ?: return TimerEngineResult.of(state, emptyList())

        val elapsedSinceRing = now - startedAt
        val firstEnd = startedAt + REPEAT_WAIT_AFTER_AUTO_STOP_MS // 隐含的"首次结束"时间点

        // 1) 首次 30s 期内 → 补首次提醒剩余时长
        if (elapsedSinceRing < REPEAT_WAIT_AFTER_AUTO_STOP_MS) {
            val remaining = REPEAT_WAIT_AFTER_AUTO_STOP_MS - elapsedSinceRing
            return TimerEngineResult.of(
                state,
                listOf(TimerEffect.StartReminder(state.phase, remaining))
            )
        }

        // 2) 已触发过重复 → 不动作
        if (state.repeatReminderFired) {
            return TimerEngineResult.of(state, emptyList())
        }

        // 3) 已存在明确的等待窗口（持久化字段）→ 不动作，等 OnTick 自然推进
        if (state.awaitingRepeatSinceEpochMillis != null) {
            return TimerEngineResult.of(state, emptyList())
        }

        // 4) 异常路径：awaiting == null 且首次已结束 → 按隐含时间戳归一化
        //    4a) 隐含窗口未到（now < firstEnd + 3min）→ 锚 awaiting 到 firstEnd
        //    4b) 隐含窗口已到 → 补 15s 重复 + 标记
        val untilWindowEnd = firstEnd + REPEAT_WINDOW_MS - now
        return if (untilWindowEnd > 0) {
            val updated = state.copy(awaitingRepeatSinceEpochMillis = firstEnd)
            TimerEngineResult.of(
                updated,
                listOf(
                    TimerEffect.SaveRuntime(updated),
                    TimerEffect.UpdateNotification(0L, updated.phase)
                )
            )
        } else {
            val updated = state.copy(
                awaitingRepeatSinceEpochMillis = null,
                repeatReminderFired = true
            )
            TimerEngineResult.of(
                updated,
                listOf(
                    TimerEffect.SaveRuntime(updated),
                    TimerEffect.StartReminder(updated.phase, REPEAT_REMINDER_DURATION_MS),
                    TimerEffect.UpdateNotification(0L, updated.phase)
                )
            )
        }
    }

    // ===== 辅助：下一阶段 + 时长（基于 runtime 快照） =====

    /**
     * v0.2 P1 修复：Engine 自己决定下一阶段，避免"通知 action 绕开 ViewModel"导致
     * 轮次错乱。
     *
     * 规则：
     * - FOCUS 完成 → 若 `cyclePositionAtStart + 1 >= cyclesBeforeLongBreakAtStart` → LONG_BREAK
     *                否则 → SHORT_BREAK
     * - SHORT_BREAK 完成 → FOCUS
     * - LONG_BREAK 完成 → FOCUS
     */
    fun computeNextPhase(state: TimerRuntimeState, completedPhase: TimerPhase): TimerPhase =
        when (completedPhase) {
            TimerPhase.FOCUS -> {
                val posAfter = state.cyclePositionAtStart + 1
                if (posAfter >= state.cyclesBeforeLongBreakAtStart) TimerPhase.LONG_BREAK
                else TimerPhase.SHORT_BREAK
            }
            TimerPhase.SHORT_BREAK, TimerPhase.LONG_BREAK -> TimerPhase.FOCUS
        }

    /**
     * v0.2 P1 修复：基于 runtime 快照计算下一阶段时长，避免用户中途改设置跳变。
     */
    fun durationFor(state: TimerRuntimeState, phase: TimerPhase): Long = when (phase) {
        TimerPhase.FOCUS -> state.focusMinutesAtStart * 60_000L
        TimerPhase.SHORT_BREAK -> state.shortBreakMinutesAtStart * 60_000L
        TimerPhase.LONG_BREAK -> state.longBreakMinutesAtStart * 60_000L
    }

    /**
     * v0.2 §6: 根据当前轮次位置与配置决定下一个休息阶段。
     *
     * - 决策规则：`pos >= cycles` → LONG_BREAK；否则 SHORT_BREAK
     * - 注：本函数主要用于测试/调试——主流程已统一在 [computeNextPhase] 中
     *   通过 runtime 快照计算
     */
    fun nextPhase(
        @Suppress("UNUSED_PARAMETER") history: List<TimerSession>,
        cyclePosition: Int,
        cyclesBeforeLongBreak: Int
    ): TimerPhase {
        val cycles = cyclesBeforeLongBreak.coerceAtLeast(1)
        val pos = cyclePosition.coerceAtLeast(0)
        return if (pos >= cycles) TimerPhase.LONG_BREAK else TimerPhase.SHORT_BREAK
    }

    // ===== 工具 =====

    private fun generateSessionId(): Long =
        Random.nextLong().let { if (it < 0) -it else it }

    // ===== §4 调度常量 =====

    /**
     * 首次提醒"已自动停止"判定阈值（30 秒）。
     */
    const val REPEAT_WAIT_AFTER_AUTO_STOP_MS = 30_000L

    /**
     * 重复提醒"等待用户新动作"的窗口长度（3 分钟）。
     */
    const val REPEAT_WINDOW_MS = 3L * 60L * 1000L

    // ===== 提醒时长常量 =====

    /**
     * 首次提醒持续时长（30 秒）。
     */
    const val FIRST_REMINDER_DURATION_MS = 30_000L

    /**
     * §4 重复提醒持续时长（15 秒）。
     */
    const val REPEAT_REMINDER_DURATION_MS = 15_000L

    /**
     * Respond.ContinueFocus 单次延长 5 分钟。
     */
    const val EXTEND_FOCUS_MS = 5L * 60L * 1000L
}

/**
 * 工具函数：PAUSED 状态下的剩余时间。
 */
fun remainingMillis(now: Long, state: TimerRuntimeState): Long {
    if (state.runState == TimerRunState.PAUSED) {
        val anchor = state.pausedAtEpochMillis ?: return state.plannedDurationMillis
        return (state.targetEndAtEpochMillis - anchor).coerceAtLeast(0L)
    }
    return (state.targetEndAtEpochMillis - now).coerceAtLeast(0L)
}

/**
 * 工具函数：实际专注毫秒。
 *
 * - RUNNING: `min(now, targetEnd)` —— 已超 planned 时封顶在 planned
 * - PAUSED: `pausedAt` —— 冻结在暂停时刻
 * - RINGING: `targetEnd` —— 等待用户响应的时间不计入专注
 * - IDLE / FINISHED: `targetEnd`
 */
fun actualFocusMillis(now: Long, state: TimerRuntimeState): Long {
    val end = when (state.runState) {
        TimerRunState.RUNNING -> minOf(now, state.targetEndAtEpochMillis)
        TimerRunState.PAUSED -> state.pausedAtEpochMillis ?: state.targetEndAtEpochMillis
        TimerRunState.RINGING,
        TimerRunState.IDLE,
        TimerRunState.FINISHED -> state.targetEndAtEpochMillis
    }
    return (end - state.startedAtEpochMillis - state.accumulatedPausedMillis).coerceAtLeast(0L)
}
