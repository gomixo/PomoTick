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
 * - [FOCUS_CYCLES_BEFORE_LONG_BREAK] 完成几个专注后进入长休息（v0.2 §6 新增，2-6，默认 3）
 * - [CYCLE_POSITION] 当前轮次中的已完成专注数（v0.2 §6 新增，0..cycles，默认 0）
 *   - 持久化为"用户进度"的一部分，重启 App 后保留
 *   - FOCUS 完成 → +1；LONG_BREAK 完成 → 0
 * - [VIBRATION_STRENGTH] 震动强度（0=关/1=弱/2=强），默认 2
 * - [RINGTONE_ENABLED] 是否在 RINGING 状态播放铃声（v0.2 新增），默认 true
 * - [PERSISTENT_REMINDER] 旧版"每 30s 重复 10 次"开关，**v0.2 起废弃**
 *   —— §3 改为"30s 自动停止"，§4 改为"3min 后 1 次 15s 重复"。保留字段仅
 *   用于兼容已有用户 DataStore，**新代码不应再读取**。
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    object Keys {
        val FOCUS_MINUTES = intPreferencesKey("settings_focus_minutes")
        val SHORT_BREAK_MINUTES = intPreferencesKey("settings_short_break_minutes")
        val LONG_BREAK_MINUTES = intPreferencesKey("settings_long_break_minutes")
        val FOCUS_CYCLES_BEFORE_LONG_BREAK = intPreferencesKey("settings_focus_cycles")
        val CYCLE_POSITION = intPreferencesKey("settings_cycle_position")
        val VIBRATION_STRENGTH = intPreferencesKey("settings_vibration_strength")
        val RINGTONE_ENABLED = booleanPreferencesKey("settings_ringtone_enabled")
        val PERSISTENT_REMINDER = booleanPreferencesKey("settings_persistent_reminder")
        val HAS_SHOWN_BATTERY_HINT = booleanPreferencesKey("settings_has_shown_battery_hint")
        val SELECTED_PHASE = stringPreferencesKey("settings_selected_phase")
        val LAST_LAUNCH_DATE = stringPreferencesKey("settings_last_launch_date")
    }

    val focusMinutes: Flow<Int> = dataStore.data.map { it[Keys.FOCUS_MINUTES] ?: 25 }
    val shortBreakMinutes: Flow<Int> = dataStore.data.map { it[Keys.SHORT_BREAK_MINUTES] ?: 5 }
    val longBreakMinutes: Flow<Int> = dataStore.data.map { it[Keys.LONG_BREAK_MINUTES] ?: 15 }
    val focusCyclesBeforeLongBreak: Flow<Int> = dataStore.data.map {
        it[Keys.FOCUS_CYCLES_BEFORE_LONG_BREAK] ?: 3
    }
    val cyclePosition: Flow<Int> = dataStore.data.map { it[Keys.CYCLE_POSITION] ?: 0 }
    val vibrationStrength: Flow<Int> = dataStore.data.map { it[Keys.VIBRATION_STRENGTH] ?: 2 }
    val ringtoneEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.RINGTONE_ENABLED] ?: true }
    val persistentReminder: Flow<Boolean> = dataStore.data.map { it[Keys.PERSISTENT_REMINDER] ?: true }
    val hasShownBatteryHint: Flow<Boolean> = dataStore.data.map { it[Keys.HAS_SHOWN_BATTERY_HINT] ?: false }
    val selectedPhase: Flow<TimerPhase> = dataStore.data.map { prefs ->
        prefs[Keys.SELECTED_PHASE]
            ?.let { runCatching { TimerPhase.valueOf(it) }.getOrNull() }
            ?: TimerPhase.FOCUS
    }
    val lastLaunchDate: Flow<String?> = dataStore.data.map { it[Keys.LAST_LAUNCH_DATE] }

    suspend fun setFocusMinutes(minutes: Int) {
        dataStore.edit { it[Keys.FOCUS_MINUTES] = minutes.coerceIn(5, 45) }
    }

    suspend fun setShortBreakMinutes(minutes: Int) {
        dataStore.edit { it[Keys.SHORT_BREAK_MINUTES] = minutes.coerceIn(1, 60) }
    }

    /**
     * v0.2 §7: 长休息时长只允许 10/15/20 分钟三档（其他值取最近邻）。
     *
     * - 任意正数 → 映射到 {10, 15, 20} 中距离最近的一档
     * - 非正数 → 10（防御）
     */
    suspend fun setLongBreakMinutes(minutes: Int) {
        val preset = snapLongBreakPreset(minutes)
        dataStore.edit { it[Keys.LONG_BREAK_MINUTES] = preset }
    }

    private fun snapLongBreakPreset(minutes: Int): Int {
        if (minutes <= 0) return 10
        val presets = intArrayOf(10, 15, 20)
        return presets.minBy { kotlin.math.abs(it - minutes) }
    }

    /**
     * 设置"几个专注后进入长休息"——v0.2 §6 要求 2-6。
     */
    suspend fun setFocusCyclesBeforeLongBreak(n: Int) {
        dataStore.edit { it[Keys.FOCUS_CYCLES_BEFORE_LONG_BREAK] = n.coerceIn(2, 6) }
    }

    /**
     * 设置当前轮次位置（已完成专注数）。
     *
     * 写时**不**限制上限——由调用方（[com.pomotick.ui.TimerViewModel]）传入
     * `(currentPos + 1).coerceAtMost(cyclesBeforeLongBreak)`，持久化时仍允许任意非负值
     * 以便支持"动态调小 cycles"场景下的历史位置。
     */
    suspend fun setCyclePosition(n: Int) {
        dataStore.edit { it[Keys.CYCLE_POSITION] = n.coerceAtLeast(0) }
    }

    suspend fun setVibrationStrength(strength: Int) {
        dataStore.edit { it[Keys.VIBRATION_STRENGTH] = strength.coerceIn(0, 2) }
    }

    suspend fun setRingtoneEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.RINGTONE_ENABLED] = enabled }
    }

    /** @deprecated 旧版"每 30s 重复 10 次"开关，v0.2 起不再读取。 */
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
    val focusCyclesBeforeLongBreak: Int = 3,
    val cyclePosition: Int = 0,
    val vibrationStrength: Int = 2,
    val ringtoneEnabled: Boolean = true,
    val persistentReminder: Boolean = true,
    val hasShownBatteryHint: Boolean = false,
    val selectedPhase: TimerPhase = TimerPhase.FOCUS,
    val lastLaunchDate: String? = null
) {
    companion object {
        val DEFAULT = SettingsSnapshot()
    }
}
