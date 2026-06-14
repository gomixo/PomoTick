package com.pomotick.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pomotick.timer.TimerPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 用户设置持久化（DataStore Preferences）。
 *
 * 字段：
 * - [FOCUS_MINUTES] 专注时长（分钟），默认 25
 * - [SHORT_BREAK_MINUTES] 短休息时长（分钟），默认 5
 * - [LONG_BREAK_MINUTES] 长休息时长（分钟），默认 15
 * - [VIBRATION_STRENGTH] 震动强度（0=关/1=弱/2=强），默认 2
 * - [PERSISTENT_REMINDER] 是否启用持续重复提醒，默认 true
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    object Keys {
        val FOCUS_MINUTES = intPreferencesKey("settings_focus_minutes")
        val SHORT_BREAK_MINUTES = intPreferencesKey("settings_short_break_minutes")
        val LONG_BREAK_MINUTES = intPreferencesKey("settings_long_break_minutes")
        val VIBRATION_STRENGTH = intPreferencesKey("settings_vibration_strength")
        val PERSISTENT_REMINDER = booleanPreferencesKey("settings_persistent_reminder")
        val HAS_SHOWN_BATTERY_HINT = booleanPreferencesKey("settings_has_shown_battery_hint")
        val SELECTED_PHASE = stringPreferencesKey("settings_selected_phase")
        val LAST_LAUNCH_DATE = stringPreferencesKey("settings_last_launch_date")
    }

    val focusMinutes: Flow<Int> = dataStore.data.map { it[Keys.FOCUS_MINUTES] ?: 25 }
    val shortBreakMinutes: Flow<Int> = dataStore.data.map { it[Keys.SHORT_BREAK_MINUTES] ?: 5 }
    val longBreakMinutes: Flow<Int> = dataStore.data.map { it[Keys.LONG_BREAK_MINUTES] ?: 15 }
    val vibrationStrength: Flow<Int> = dataStore.data.map { it[Keys.VIBRATION_STRENGTH] ?: 2 }
    val persistentReminder: Flow<Boolean> = dataStore.data.map { it[Keys.PERSISTENT_REMINDER] ?: true }
    val hasShownBatteryHint: Flow<Boolean> = dataStore.data.map { it[Keys.HAS_SHOWN_BATTERY_HINT] ?: false }
    val selectedPhase: Flow<TimerPhase> = dataStore.data.map { prefs ->
        prefs[Keys.SELECTED_PHASE]
            ?.let { runCatching { TimerPhase.valueOf(it) }.getOrNull() }
            ?: TimerPhase.FOCUS
    }
    val lastLaunchDate: Flow<String?> = dataStore.data.map { it[Keys.LAST_LAUNCH_DATE] }

    suspend fun setFocusMinutes(minutes: Int) {
        dataStore.edit { it[Keys.FOCUS_MINUTES] = minutes.coerceIn(1, 180) }
    }

    suspend fun setShortBreakMinutes(minutes: Int) {
        dataStore.edit { it[Keys.SHORT_BREAK_MINUTES] = minutes.coerceIn(1, 60) }
    }

    suspend fun setLongBreakMinutes(minutes: Int) {
        dataStore.edit { it[Keys.LONG_BREAK_MINUTES] = minutes.coerceIn(1, 120) }
    }

    suspend fun setVibrationStrength(strength: Int) {
        dataStore.edit { it[Keys.VIBRATION_STRENGTH] = strength.coerceIn(0, 2) }
    }

    suspend fun setPersistentReminder(enabled: Boolean) {
        dataStore.edit { it[Keys.PERSISTENT_REMINDER] = enabled }
    }

    suspend fun markBatteryHintShown() {
        dataStore.edit { it[Keys.HAS_SHOWN_BATTERY_HINT] = true }
    }

    suspend fun setSelectedPhase(phase: TimerPhase) {
        dataStore.edit { it[Keys.SELECTED_PHASE] = phase.name }
    }

    suspend fun setLastLaunchDate(date: String) {
        dataStore.edit { it[Keys.LAST_LAUNCH_DATE] = date }
    }

    companion object {
        fun from(context: Context): SettingsStore = SettingsStore(context.applicationContext.pomotickDataStore)
    }
}

/**
 * 全局 DataStore 委托（单实例）。
 *
 * 必须定义在**文件顶层**而非 companion object 内，以便跨包访问。
 */
val Context.pomotickDataStore: DataStore<Preferences> by preferencesDataStore(name = "pomotick_prefs")

/**
 * 设置快照（用于 ViewModel 一次性读取，避免 Flow 重组）。
 */
data class SettingsSnapshot(
    val focusMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    val vibrationStrength: Int = 2,
    val persistentReminder: Boolean = true,
    val hasShownBatteryHint: Boolean = false,
    val selectedPhase: TimerPhase = TimerPhase.FOCUS,
    val lastLaunchDate: String? = null
) {
    companion object {
        val DEFAULT = SettingsSnapshot()
    }
}
