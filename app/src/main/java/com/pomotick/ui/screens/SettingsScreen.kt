package com.pomotick.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pomotick.PomoTickApp
import com.pomotick.R
import com.pomotick.ui.TimerViewModel
import com.pomotick.ui.components.BigButton
import kotlinx.coroutines.launch

/**
 * 设置屏——时长配置、震动强度、持续提醒开关。
 */
@Composable
fun SettingsScreen(
    viewModel: TimerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as PomoTickApp
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            fontSize = 18.sp
        )

        // Focus 时长
        SettingRow(
            label = stringResource(R.string.settings_focus_minutes),
            value = "${state.settings.focusMinutes}m",
            onMinus = {
                scope.launch { app.settingsStore.setFocusMinutes(state.settings.focusMinutes - 5) }
            },
            onPlus = {
                scope.launch { app.settingsStore.setFocusMinutes(state.settings.focusMinutes + 5) }
            }
        )

        // 短休息
        SettingRow(
            label = stringResource(R.string.settings_short_break),
            value = "${state.settings.shortBreakMinutes}m",
            onMinus = {
                scope.launch { app.settingsStore.setShortBreakMinutes(state.settings.shortBreakMinutes - 1) }
            },
            onPlus = {
                scope.launch { app.settingsStore.setShortBreakMinutes(state.settings.shortBreakMinutes + 1) }
            }
        )

        // 长休息
        SettingRow(
            label = stringResource(R.string.settings_long_break),
            value = "${state.settings.longBreakMinutes}m",
            onMinus = {
                scope.launch { app.settingsStore.setLongBreakMinutes(state.settings.longBreakMinutes - 5) }
            },
            onPlus = {
                scope.launch { app.settingsStore.setLongBreakMinutes(state.settings.longBreakMinutes + 5) }
            }
        )

        // 震动强度
        VibrationStrengthRow(
            current = state.settings.vibrationStrength,
            onSelect = { strength ->
                scope.launch { app.settingsStore.setVibrationStrength(strength) }
            }
        )

        // 持续提醒开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.settings_persistent_reminder),
                fontSize = 14.sp
            )
            Switch(
                checked = state.settings.persistentReminder,
                onCheckedChange = { enabled ->
                    scope.launch { app.settingsStore.setPersistentReminder(enabled) }
                }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        BigButton(
            label = stringResource(R.string.action_back),
            onClick = onBack,
            primary = false
        )
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = label, fontSize = 12.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BigButton(label = "-", onClick = onMinus, modifier = Modifier.weight(1f), primary = false)
            Text(text = value, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 12.dp))
            BigButton(label = "+", onClick = onPlus, modifier = Modifier.weight(1f), primary = false)
        }
    }
}

@Composable
private fun VibrationStrengthRow(
    current: Int,
    onSelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = stringResource(R.string.settings_vibration_strength), fontSize = 12.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            BigButton(
                label = stringResource(R.string.vibration_off),
                onClick = { onSelect(0) },
                modifier = Modifier.weight(1f),
                primary = current == 0
            )
            BigButton(
                label = stringResource(R.string.vibration_low),
                onClick = { onSelect(1) },
                modifier = Modifier.weight(1f),
                primary = current == 1
            )
            BigButton(
                label = stringResource(R.string.vibration_high),
                onClick = { onSelect(2) },
                modifier = Modifier.weight(1f),
                primary = current == 2
            )
        }
    }
}
