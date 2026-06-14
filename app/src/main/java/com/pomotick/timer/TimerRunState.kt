package com.pomotick.timer

/**
 * 计时运行状态机。
 *
 * 状态迁移图：
 * ```
 * IDLE → RUNNING ⇄ PAUSED → RINGING → IDLE
 *                   │
 *                   └→ FINISHED (历史 session 已记录)
 * ```
 *
 * 注：[FINISHED] 当前在 MVP 中保留但不显式进入；用户响应 RINGING 后回到 IDLE。
 */
enum class TimerRunState {
    /** 空闲，无活跃 timer */
    IDLE,

    /** 正在倒计时 */
    RUNNING,

    /** 已暂停（时间冻结） */
    PAUSED,

    /** 已到点，等待用户响应（震动提醒中） */
    RINGING,

    /** 已完成（已写历史 session，清空 runtime）—— 当前未单独保留，RINGING → IDLE 即表示完成 */
    FINISHED
}
