package com.pomotick.timer

/**
 * 用户 / 系统事件（输入到 [TimerEngine]）。
 */
sealed class TimerEvent {
    /**
     * 开始新计时。
     *
     * @param now 当前 epoch millis
     * @param phase 阶段
     * @param plannedMs 计划时长
     */
    data class Start(
        val now: Long,
        val phase: TimerPhase,
        val plannedMs: Long
    ) : TimerEvent()

    /** 系统 tick（service 每 2s 调用一次） */
    data class OnTick(val now: Long) : TimerEvent()

    /** 暂停 */
    data class Pause(val now: Long) : TimerEvent()

    /** 恢复（必须从 PAUSED 状态） */
    data class Resume(val now: Long) : TimerEvent()

    /** 延长 deltaMs（默认 5 分钟） */
    data class Extend(val now: Long, val deltaMs: Long = 5L * 60L * 1000L) : TimerEvent()

    /**
     * 提前结束（用户主动）。
     *
     * 立即记录 status=COMPLETED 的 session，然后进入 RINGING。
     */
    data class FinishEarly(val now: Long) : TimerEvent()

    /** 放弃（用户主动放弃当前计时） */
    data class Abandon(val now: Long) : TimerEvent()

    /** Stop the active timer/reminder and return to the selected phase's full duration. */
    data class Reset(val now: Long, val phase: TimerPhase? = null) : TimerEvent()

    /** Change the idle start screen phase without starting the timer. */
    data class SwitchPhase(val phase: TimerPhase) : TimerEvent()

    /** Stop the ringing reminder, record completion, and prepare the next idle phase. */
    data class StopRingingAndPrepareNext(val now: Long) : TimerEvent()

    /**
     * RINGING 响应。
     *
     * - [ResponseAction.KnowIt] → 写 COMPLETED session（若尚未写） → 清 runtime → IDLE
     * - [ResponseAction.StartBreak] → 写 session → 启动 [options] 指定的新阶段
     * - [ResponseAction.ContinueFocus] → 延长 5 分钟 → 继续 RUNNING
     *
     * @param options StartBreak 需要的 phase / plannedMs；其他 action 忽略
     */
    data class Respond(
        val now: Long,
        val action: ResponseAction,
        val options: RespondOptions = RespondOptions()
    ) : TimerEvent()
}

/**
 * RINGING 状态下的用户响应动作。
 */
enum class ResponseAction {
    /** 知道了：结束当前 session */
    KnowIt,

    /** 开始休息 */
    StartBreak,

    /** 继续专注（等同于 Extend 5min） */
    ContinueFocus
}

/**
 * [TimerEvent.Respond] 的可选参数。
 *
 * - [nextPhase] StartBreak 时指定下一个阶段（LONG_BREAK / SHORT_BREAK）
 * - [plannedMs] StartBreak 时指定下一个阶段的计划时长
 *
 * 调用方（ViewModel / Repository）负责根据 settings + history 计算并传入；
 * 不传入则 Engine 使用默认（SHORT_BREAK + 5 分钟）。
 */
data class RespondOptions(
    val nextPhase: TimerPhase? = null,
    val plannedMs: Long? = null
)
