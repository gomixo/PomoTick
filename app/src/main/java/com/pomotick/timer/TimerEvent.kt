package com.pomotick.timer

/**
 * 引擎事件（sealed）。
 *
 * v0.2 关键变化：
 * - `Start` 携带 5 个配置快照字段（`focusMinutesAtStart` / `shortBreakMinutesAtStart` /
 *   `longBreakMinutesAtStart` / `cyclesBeforeLongBreakAtStart` / `cyclePositionAtStart`），
 *   用于 Engine 在不依赖 SettingsStore 的前提下计算"下一阶段 + plannedMs"
 * - 新增 `StopRingingOnly`——只停声震、保持 RINGING、启动 §4 等待窗口
 *   （区别于 `StopRingingAndPrepareNext` 真正"完成 → 推进阶段"）
 * - `Respond` 不再携带 `RespondOptions`——Engine 用 runtime 快照自己决定
 */
sealed class TimerEvent {

    /**
     * v0.2 §9：开始新计时。
     *
     * @param now 当前 epoch millis
     * @param phase 阶段
     * @param plannedMs 计划时长
     * @param cyclePositionAtStart 开始时的轮次位置快照（用于运行时状态恢复）
     * @param longBreakMinutesAtStart 开始时的长休息分钟数快照（防止中途改设置跳变）
     * @param shortBreakMinutesAtStart 开始时的短休息分钟数快照
     * @param focusMinutesAtStart 开始时的专注分钟数快照
     * @param cyclesBeforeLongBreakAtStart 开始时的"几个 FOCUS 后长休息"快照
     */
    data class Start(
        val now: Long,
        val phase: TimerPhase,
        val plannedMs: Long,
        val cyclePositionAtStart: Int = 0,
        val longBreakMinutesAtStart: Int = 15,
        val shortBreakMinutesAtStart: Int = 5,
        val focusMinutesAtStart: Int = 25,
        val cyclesBeforeLongBreakAtStart: Int = 3
    ) : TimerEvent()

    data class Pause(val now: Long) : TimerEvent()
    data class Resume(val now: Long) : TimerEvent()
    data class Extend(val now: Long, val extraMs: Long) : TimerEvent()
    data class FinishEarly(val now: Long) : TimerEvent()
    data class Abandon(val now: Long) : TimerEvent()
    data class OnTick(val now: Long) : TimerEvent()
    data class SwitchPhase(val phase: TimerPhase) : TimerEvent()
    data class Reset(val selectedPhase: TimerPhase) : TimerEvent()

    /**
     * 用户在 RINGING 屏对提醒做出响应。
     *
     * 不再携带 RespondOptions——Engine 用 runtime 的 5 个配置快照决定"下一阶段 +
     * plannedMs"。这样 UI 按钮与通知 Action 走完全相同的决策路径。
     */
    data class Respond(
        val now: Long,
        val action: ResponseAction
    ) : TimerEvent()

    /**
     * v0.2 P1 新增：仅停声震，保持 RINGING + 启动 §4 等待窗口。
     *
     * 通知"停止"Action 的入口。UI 上对应"停止声震"按钮。
     */
    data class StopRingingOnly(val now: Long) : TimerEvent()

    /**
     * v0.2 P1 修复：停声震 + 写 session + 推进轮次 + 进入下一阶段。
     *
     * 这是"知道了"和"开始下一阶段"的真正入口。Engine 用 runtime 快照决定
     * 下一阶段（避免 ViewModel 计算绕开通知 Action）。
     */
    data class StopRingingAndPrepareNext(val now: Long) : TimerEvent()

    /**
     * v0.2 P1 修复：App/Service 重启时，按持久化时间戳计算"还需响多久"，
     * 补一次有限时长的 StartReminder。Engine 收到后仅发 [TimerEffect.StartReminder]，
     * 不修改 runtime 状态（也不清 §4 调度）。
     */
    data class ResumeReminder(
        val now: Long,
        val phase: TimerPhase,
        /** 距离上次 ringingStartedAt 还能响的毫秒数（首次 30s 或重复 15s 剩余）。 */
        val remainingMs: Long
    ) : TimerEvent()

    /**
     * v0.2 第三轮 P1 修复：冷启动恢复时把"如何归一化 RINGING 状态"的决策权
     * 完全交给 Engine，而不是 ViewModel 直接拼 effect。
     *
     * 与 [TimerEvent.ResumeReminder] 的区别：本事件会让 Engine **修改 runtime**——
     * 包括按隐含时间戳（ringingStartedAt + 30s）建立 [TimerRuntimeState.awaitingRepeatSinceEpochMillis]
     * 或标记 [TimerRuntimeState.repeatReminderFired]——保证后续 OnTick 不会再补一次重复提醒。
     */
    data class RingingRecovered(val now: Long) : TimerEvent()
}

enum class ResponseAction {
    /** 用户只确认看到提醒，不切换阶段。 */
    KnowIt,

    /** 用户选择进入下一休息阶段。 */
    StartBreak,

    /** 用户延长 5 分钟继续专注。 */
    ContinueFocus
}
