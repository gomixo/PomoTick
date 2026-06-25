package com.pomotick.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pomotick.PomoTickApp
import com.pomotick.R
import com.pomotick.data.SettingsSnapshot
import com.pomotick.ui.TimerViewModel
import com.pomotick.ui.components.BigButton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 设置屏——v0.2 §7 改版后精简为 5 项：
 *
 * 1. 专注时长
 * 2. 短休息
 * 3. 长休息（v0.2 §7: 仅 10/15/20 三档）
 * 4. 专注轮次（v0.2 §7 新增，2-6）
 * 5. 震动强度
 * 6. 响铃（v0.2 §3 新增，替代旧"持续提醒"开关）
 *
 * **单层、无嵌套**——所有控件都在一个 Column 内展开。
 * 通过左滑进入（v0.2 §7），返回通过左滑/右滑或 `onBack` 按钮回到主界面。
 */
@Composable
fun SettingsScreen(
    viewModel: TimerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // v0.2 第四轮 P0 性能修复：设置页**只**订阅 settings，**不**订阅 runState /
    // remainingMs / phase。主计时器每秒倒计时变化时，设置页不参与重组。
    //
    // v0.2 第五轮 P0：initialValue 用 `viewModel.baseState.value.settings`
    // 取代 `SettingsSnapshot.DEFAULT`，这样 `baseState.IDLE.settings` 已经是默认
    // 真实值（25min/5min/15min/3/2/true/false），第一次组合时 SettingsScreen 拿到
    // 的就是"用户后续会看到的"值，避免收到 observeSettings 第一次推送后的二次重组。
    val settings by remember(viewModel) {
        viewModel.baseState
            .map { it.settings }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = viewModel.baseState.value.settings
    )
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as PomoTickApp
    val scope = rememberCoroutineScope()

    // v0.2.1: 精确闹钟权限状态——息屏到点提醒可靠性的关键。
    // 用户拒了 SCHEDULE_EXACT_ALARM 时降级到 setAndAllowWhileIdle，误差可能达数分钟。
    val exactAlarmGranted = remember(app) {
        app.alarmScheduler.canScheduleExactAlarms()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )

        // 1. 专注时长
        SettingRow(
            label = stringResource(R.string.settings_focus_minutes),
            value = "${settings.focusMinutes}m",
            onMinus = {
                scope.launch { app.settingsStore.setFocusMinutes(settings.focusMinutes - 5) }
            },
            onPlus = {
                scope.launch { app.settingsStore.setFocusMinutes(settings.focusMinutes + 5) }
            }
        )

        // 2. 短休息
        SettingRow(
            label = stringResource(R.string.settings_short_break),
            value = "${settings.shortBreakMinutes}m",
            onMinus = {
                scope.launch { app.settingsStore.setShortBreakMinutes(settings.shortBreakMinutes - 1) }
            },
            onPlus = {
                scope.launch { app.settingsStore.setShortBreakMinutes(settings.shortBreakMinutes + 1) }
            }
        )

        // 3. 长休息
        SettingRow(
            label = stringResource(R.string.settings_long_break),
            value = "${settings.longBreakMinutes}m",
            onMinus = {
                scope.launch { app.settingsStore.setLongBreakMinutes(settings.longBreakMinutes - 5) }
            },
            onPlus = {
                scope.launch { app.settingsStore.setLongBreakMinutes(settings.longBreakMinutes + 5) }
            }
        )

        // 4. 专注轮次
        SettingRow(
            label = stringResource(R.string.settings_focus_cycles),
            value = settings.focusCyclesBeforeLongBreak.toString(),
            onMinus = {
                scope.launch {
                    app.settingsStore.setFocusCyclesBeforeLongBreak(
                        settings.focusCyclesBeforeLongBreak - 1
                    )
                }
            },
            onPlus = {
                scope.launch {
                    app.settingsStore.setFocusCyclesBeforeLongBreak(
                        settings.focusCyclesBeforeLongBreak + 1
                    )
                }
            }
        )

        // 5. 震动强度
        VibrationToggleRow(
            current = settings.vibrationStrength,
            onToggle = {
                val next = (settings.vibrationStrength + 1) % 3
                scope.launch { app.settingsStore.setVibrationStrength(next) }
            }
        )

        // 6. 响铃开关
        androidx.compose.material3.Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.settings_ringtone_enabled),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
                Switch(
                    checked = settings.ringtoneEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { app.settingsStore.setRingtoneEnabled(enabled) }
                    }
                )
            }
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
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AdjustButton(label = "-", onClick = onMinus)
                Text(
                    text = value,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(40.dp),
                    textAlign = TextAlign.Center
                )
                AdjustButton(label = "+", onClick = onPlus)
            }
        }
    }
}

@Composable
private fun AdjustButton(
    label: String,
    onClick: () -> Unit
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = Modifier.size(42.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 20.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun VibrationToggleRow(
    current: Int,
    onToggle: () -> Unit
) {
    androidx.compose.material3.Surface(
        onClick = onToggle,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_vibration_strength),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
            Text(
                text = when(current) {
                    0 -> stringResource(R.string.vibration_off)
                    1 -> stringResource(R.string.vibration_low)
                    else -> stringResource(R.string.vibration_high)
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * v0.2.1: 精确闹钟权限状态横幅。
 *
 * - **已授权**（绿色 ✓）：息屏到点唤醒可靠
 * - **未授权**（红色 ⚠）：降级到 `setAndAllowWhileIdle`，**不**保证到点准确，
 *   误差可能达数分钟。强烈建议用户去系统设置授权。
 *
 * 仅展示状态；点横幅不跳转（避免增加额外的 settings deeplink 依赖）。
 */
@Composable
private fun ExactAlarmStatusRow(granted: Boolean) {
    val containerColor = if (granted) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
    }
    val contentColor = if (granted) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    androidx.compose.material3.Surface(
        color = containerColor,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp)
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(
                    if (granted) R.string.settings_exact_alarm_granted
                    else R.string.settings_exact_alarm_denied
                ),
                fontSize = 11.sp,
                color = contentColor
            )
        }
    }
}
