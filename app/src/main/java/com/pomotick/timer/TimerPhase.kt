package com.pomotick.timer

/**
 * 番茄计时阶段。
 */
enum class TimerPhase {
    /** 专注阶段（默认 25 分钟） */
    FOCUS,

    /** 短休息（默认 5 分钟） */
    SHORT_BREAK,

    /** 长休息（每完成 4 个 FOCUS 后出现，默认 15 分钟） */
    LONG_BREAK;

    val displayName: String
        get() = when (this) {
            FOCUS -> "focus"
            SHORT_BREAK -> "short_break"
            LONG_BREAK -> "long_break"
        }
}
