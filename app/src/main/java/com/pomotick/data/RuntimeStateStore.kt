package com.pomotick.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
 */
class RuntimeStateStore(private val dataStore: DataStore<Preferences>) {

    object Keys {
        val SESSION_ID = longPreferencesKey("runtime_session_id")
        val PHASE = stringPreferencesKey("runtime_phase")
        val RUN_STATE = stringPreferencesKey("runtime_run_state")
        val PLANNED_DURATION = longPreferencesKey("runtime_planned_duration")
        val STARTED_AT = longPreferencesKey("runtime_started_at")
        val TARGET_END = longPreferencesKey("runtime_target_end")
        val PAUSED_AT = longPreferencesKey("runtime_paused_at")       // 0L 表示未暂停
        val ACCUMULATED_PAUSED = longPreferencesKey("runtime_accumulated_paused")
        val EXTENSION_COUNT = longPreferencesKey("runtime_extension_count")
        val HAS_RUNTIME = booleanPreferencesKey("runtime_has_state")
        val SESSION_COMPLETION_RECORDED = booleanPreferencesKey("runtime_session_completion_recorded")
    }

    /**
     * 当前运行时状态流。null 表示无活跃 timer。
     */
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
                sessionCompletionRecorded = prefs[Keys.SESSION_COMPLETION_RECORDED] ?: false
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
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs[Keys.HAS_RUNTIME] = false
            // 清空其他字段（保留 keys 以观察下次写入）
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
        }
    }
}
