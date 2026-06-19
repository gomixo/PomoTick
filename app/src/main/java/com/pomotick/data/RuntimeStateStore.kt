package com.pomotick.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pomotick.timer.TimerPhase
import com.pomotick.timer.TimerRunState
import com.pomotick.timer.TimerRuntimeState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 当前活跃 timer 的运行时状态持久化（DataStore Preferences）。
 *
 * 与历史 [TimerSession] 解耦：恢复逻辑只关心"当前是否有活跃 timer + 是否过期"。
 *
 * 字段全部用独立 key，避免 JSON 序列化依赖。
 *
 * **v0.2 P1 修复——覆盖 §4 重复提醒 + §9 全部恢复字段**：
 *
 * | 字段                 | DataStore key                                  | 状态 |
 * |----------------------|------------------------------------------------|------|
 * | 当前阶段              | `runtime_phase`                                | v0.2 |
 * | 当前运行状态          | `runtime_run_state`                            | v0.2 |
 * | 开始时间戳            | `runtime_started_at`                           | v0.2 |
 * | 目标结束时间戳        | `runtime_target_end`                           | v0.2 |
 * | 暂停开始时间戳        | `runtime_paused_at`                            | v0.2 |
 * | 累计暂停毫秒          | `runtime_accumulated_paused`                   | v0.2 |
 * | 当前轮次位置快照      | `runtime_cycle_position_at_start`              | v0.2 |
 * | 长休息配置快照        | `runtime_long_break_minutes_at_start`          | v0.2 |
 * | 短休息配置快照        | `runtime_short_break_minutes_at_start`         | v0.2 P1 |
 * | 专注配置快照          | `runtime_focus_minutes_at_start`               | v0.2 P1 |
 * | 轮次阈值配置快照      | `runtime_cycles_before_long_break_at_start`    | v0.2 P1 |
 * | RINGING 进入时间戳    | `runtime_ringing_started_at`                   | v0.2 §4 P1 |
 * | 重复等待进入时间戳    | `runtime_awaiting_repeat_since`                | v0.2 §4 P1 |
 * | 是否已触发重复提醒    | `runtime_repeat_reminder_fired`                | v0.2 §4 P1 |
 * | 是否处于提醒状态      | 由 `runtime_run_state == RINGING` 推导         | v0.2 |
 */
class RuntimeStateStore(private val dataStore: DataStore<Preferences>) {

    object Keys {
        // 核心 7 个字段
        val SESSION_ID = longPreferencesKey("runtime_session_id")
        val PHASE = stringPreferencesKey("runtime_phase")
        val RUN_STATE = stringPreferencesKey("runtime_run_state")
        val PLANNED_DURATION = longPreferencesKey("runtime_planned_duration")
        val STARTED_AT = longPreferencesKey("runtime_started_at")
        val TARGET_END = longPreferencesKey("runtime_target_end")
        val PAUSED_AT = longPreferencesKey("runtime_paused_at")
        val ACCUMULATED_PAUSED = longPreferencesKey("runtime_accumulated_paused")
        val EXTENSION_COUNT = longPreferencesKey("runtime_extension_count")
        val HAS_RUNTIME = booleanPreferencesKey("runtime_has_state")
        val SESSION_COMPLETION_RECORDED = booleanPreferencesKey("runtime_session_completion_recorded")
        // §4 重复提醒调度字段（v0.2 P1 新增）
        val RINGING_STARTED_AT = longPreferencesKey("runtime_ringing_started_at")
        val AWAITING_REPEAT_SINCE = longPreferencesKey("runtime_awaiting_repeat_since")
        val REPEAT_REMINDER_FIRED = booleanPreferencesKey("runtime_repeat_reminder_fired")
        // §9 配置快照字段（v0.2 P1 扩展）
        val CYCLE_POSITION_AT_START = intPreferencesKey("runtime_cycle_position_at_start")
        val LONG_BREAK_MIN_AT_START = intPreferencesKey("runtime_long_break_minutes_at_start")
        val SHORT_BREAK_MIN_AT_START = intPreferencesKey("runtime_short_break_minutes_at_start")
        val FOCUS_MIN_AT_START = intPreferencesKey("runtime_focus_minutes_at_start")
        val CYCLES_BEFORE_LONG_BREAK_AT_START = intPreferencesKey("runtime_cycles_before_long_break_at_start")
    }

    val flow: Flow<TimerRuntimeState?> = dataStore.data.map { prefs ->
        if (prefs[Keys.HAS_RUNTIME] != true) {
            null
        } else {
            TimerRuntimeState(
                sessionId = prefs[Keys.SESSION_ID] ?: 0L,
                phase = prefs[Keys.PHASE]?.let { runCatching { TimerPhase.valueOf(it) }.getOrNull() }
                    ?: TimerPhase.FOCUS,
                runState = prefs[Keys.RUN_STATE]?.let { runCatching { TimerRunState.valueOf(it) }.getOrNull() }
                    ?: TimerRunState.IDLE,
                plannedDurationMillis = prefs[Keys.PLANNED_DURATION] ?: 0L,
                startedAtEpochMillis = prefs[Keys.STARTED_AT] ?: 0L,
                targetEndAtEpochMillis = prefs[Keys.TARGET_END] ?: 0L,
                pausedAtEpochMillis = prefs[Keys.PAUSED_AT]?.takeIf { it != 0L },
                accumulatedPausedMillis = prefs[Keys.ACCUMULATED_PAUSED] ?: 0L,
                extensionCount = (prefs[Keys.EXTENSION_COUNT] ?: 0L).toInt(),
                sessionCompletionRecorded = prefs[Keys.SESSION_COMPLETION_RECORDED] ?: false,
                // §4 字段：缺值表示未进入 RINGING / 等待 / 已触发
                ringingStartedAtEpochMillis = prefs[Keys.RINGING_STARTED_AT]?.takeIf { it != 0L },
                awaitingRepeatSinceEpochMillis = prefs[Keys.AWAITING_REPEAT_SINCE]?.takeIf { it != 0L },
                repeatReminderFired = prefs[Keys.REPEAT_REMINDER_FIRED] ?: false,
                // §9 配置快照：缺值时使用 data class 默认值（0 / 5 / 15 / 25 / 3）
                cyclePositionAtStart = prefs[Keys.CYCLE_POSITION_AT_START] ?: 0,
                longBreakMinutesAtStart = prefs[Keys.LONG_BREAK_MIN_AT_START] ?: 15,
                shortBreakMinutesAtStart = prefs[Keys.SHORT_BREAK_MIN_AT_START] ?: 5,
                focusMinutesAtStart = prefs[Keys.FOCUS_MIN_AT_START] ?: 25,
                cyclesBeforeLongBreakAtStart = prefs[Keys.CYCLES_BEFORE_LONG_BREAK_AT_START] ?: 3
            )
        }
    }

    suspend fun current(): TimerRuntimeState? = flow.first()

    suspend fun save(state: TimerRuntimeState) {
        dataStore.edit { prefs ->
            prefs[Keys.HAS_RUNTIME] = true
            prefs[Keys.SESSION_ID] = state.sessionId
            prefs[Keys.PHASE] = state.phase.name
            prefs[Keys.RUN_STATE] = state.runState.name
            prefs[Keys.PLANNED_DURATION] = state.plannedDurationMillis
            prefs[Keys.STARTED_AT] = state.startedAtEpochMillis
            prefs[Keys.TARGET_END] = state.targetEndAtEpochMillis
            prefs[Keys.PAUSED_AT] = state.pausedAtEpochMillis ?: 0L
            prefs[Keys.ACCUMULATED_PAUSED] = state.accumulatedPausedMillis
            prefs[Keys.EXTENSION_COUNT] = state.extensionCount.toLong()
            prefs[Keys.SESSION_COMPLETION_RECORDED] = state.sessionCompletionRecorded
            // §4 字段：null 写 0L 兼容旧版"无意义值"约定
            prefs[Keys.RINGING_STARTED_AT] = state.ringingStartedAtEpochMillis ?: 0L
            prefs[Keys.AWAITING_REPEAT_SINCE] = state.awaitingRepeatSinceEpochMillis ?: 0L
            prefs[Keys.REPEAT_REMINDER_FIRED] = state.repeatReminderFired
            // §9 配置快照
            prefs[Keys.CYCLE_POSITION_AT_START] = state.cyclePositionAtStart
            prefs[Keys.LONG_BREAK_MIN_AT_START] = state.longBreakMinutesAtStart
            prefs[Keys.SHORT_BREAK_MIN_AT_START] = state.shortBreakMinutesAtStart
            prefs[Keys.FOCUS_MIN_AT_START] = state.focusMinutesAtStart
            prefs[Keys.CYCLES_BEFORE_LONG_BREAK_AT_START] = state.cyclesBeforeLongBreakAtStart
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs[Keys.HAS_RUNTIME] = false
            prefs.remove(Keys.SESSION_ID)
            prefs.remove(Keys.PHASE)
            prefs.remove(Keys.RUN_STATE)
            prefs.remove(Keys.PLANNED_DURATION)
            prefs.remove(Keys.STARTED_AT)
            prefs.remove(Keys.TARGET_END)
            prefs.remove(Keys.PAUSED_AT)
            prefs.remove(Keys.ACCUMULATED_PAUSED)
            prefs.remove(Keys.EXTENSION_COUNT)
            prefs.remove(Keys.SESSION_COMPLETION_RECORDED)
            prefs.remove(Keys.RINGING_STARTED_AT)
            prefs.remove(Keys.AWAITING_REPEAT_SINCE)
            prefs.remove(Keys.REPEAT_REMINDER_FIRED)
            prefs.remove(Keys.CYCLE_POSITION_AT_START)
            prefs.remove(Keys.LONG_BREAK_MIN_AT_START)
            prefs.remove(Keys.SHORT_BREAK_MIN_AT_START)
            prefs.remove(Keys.FOCUS_MIN_AT_START)
            prefs.remove(Keys.CYCLES_BEFORE_LONG_BREAK_AT_START)
        }
    }
}
