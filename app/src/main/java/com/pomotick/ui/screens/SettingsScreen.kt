package com.pomotick.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pomotick.PomoTickApp
import com.pomotick.R
import com.pomotick.ui.TimerViewModel
import com.pomotick.ui.components.watchSafeDiameter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Settings screen — sized for the OPPO Watch 4 Pro round visible area.
 *
 * Layout (vertically scrollable, constrained to safe-area diameter):
 *  1. Title "设置"
 *  2. Section: 计时 (4 vertical rows with +/- steppers)
 *  3. Section: 提醒 (vibration toggle + ringtone switch)
 *  4. Section: 系统 (battery optimization / notification permission)
 *  5. Back button (centered pill, ≤132dp wide)
 *
 * Each row uses a vertical layout: label on top, control below.
 * Row width is constrained to 140dp to stay inside the round safe area.
 */
@Composable
fun SettingsScreen(
    viewModel: TimerViewModel,
    onBack: () -> Unit,
    onRequestNotificationPermission: () -> Unit = {},
    onRequestBatteryOptimization: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val settings by remember(viewModel) {
        viewModel.baseState
            .map { it.settings }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = viewModel.baseState.value.settings
    )
    val context = LocalContext.current
    val app = context.applicationContext as PomoTickApp
    val scope = rememberCoroutineScope()

    fun checkPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    var permissionGranted by remember { mutableStateOf(checkPermission()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionGranted = checkPermission()
    }

    val showPermissionRow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && !permissionGranted

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val safeDiameter = watchSafeDiameter()

        // Centered scroll column constrained to safe diameter
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(safeDiameter)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                // ── 计时 section ──
                SectionDivider(stringResource(R.string.settings_section_timer))

                SettingRow(
                    label = stringResource(R.string.settings_focus_minutes),
                    value = "${settings.focusMinutes}m",
                    valueColor = MaterialTheme.colorScheme.primary,
                    onMinus = {
                        scope.launch { app.settingsStore.setFocusMinutes(settings.focusMinutes - 5) }
                    },
                    onPlus = {
                        scope.launch { app.settingsStore.setFocusMinutes(settings.focusMinutes + 5) }
                    }
                )

                SettingRow(
                    label = stringResource(R.string.settings_short_break),
                    value = "${settings.shortBreakMinutes}m",
                    valueColor = MaterialTheme.colorScheme.secondary,
                    onMinus = {
                        scope.launch { app.settingsStore.setShortBreakMinutes(settings.shortBreakMinutes - 1) }
                    },
                    onPlus = {
                        scope.launch { app.settingsStore.setShortBreakMinutes(settings.shortBreakMinutes + 1) }
                    }
                )

                SettingRow(
                    label = stringResource(R.string.settings_long_break),
                    value = "${settings.longBreakMinutes}m",
                    valueColor = MaterialTheme.colorScheme.tertiary,
                    onMinus = {
                        scope.launch { app.settingsStore.setLongBreakMinutes(settings.longBreakMinutes - 5) }
                    },
                    onPlus = {
                        scope.launch { app.settingsStore.setLongBreakMinutes(settings.longBreakMinutes + 5) }
                    }
                )

                SettingRow(
                    label = stringResource(R.string.settings_focus_cycles),
                    value = settings.focusCyclesBeforeLongBreak.toString(),
                    valueColor = MaterialTheme.colorScheme.primary,
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

                // ── 提醒 section ──
                SectionDivider(stringResource(R.string.settings_section_reminder))

                VibrationToggleRow(
                    current = settings.vibrationStrength
                ) {
                    val next = (settings.vibrationStrength + 1) % 3
                    scope.launch { app.settingsStore.setVibrationStrength(next) }
                }

                RingtonRow(
                    enabled = settings.ringtoneEnabled
                ) { enabled ->
                    scope.launch { app.settingsStore.setRingtoneEnabled(enabled) }
                }

                // ── 系统 section ──
                SectionDivider(stringResource(R.string.settings_section_system))

                if (showPermissionRow) {
                    NotificationPermissionRow(onClick = onRequestNotificationPermission)
                }

                BatteryOptimizationRow(onClick = onRequestBatteryOptimization)

                Spacer(modifier = Modifier.height(8.dp))

                // Back button — centered pill, ≤132dp to avoid corner clipping
                Surface(
                    onClick = onBack,
                    modifier = Modifier.width(132.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(132.dp)
                            .height(44.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.action_back),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionDivider(label: String) {
    Text(
        text = label,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

/**
 * Vertical setting row: label on top, +/- stepper below.
 * Width constrained to 140dp to stay inside the round safe area.
 */
@Composable
private fun SettingRow(
    label: String,
    value: String,
    valueColor: Color,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .width(140.dp)
                .padding(vertical = 6.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AdjustButton(label = "−", onClick = onMinus)
                Text(
                    text = value,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = valueColor,
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.Center,
                    maxLines = 1
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
    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun VibrationToggleRow(
    current: Int,
    onToggle: () -> Unit
) {
    Surface(
        onClick = onToggle,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .width(140.dp)
                .padding(vertical = 6.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_vibration_strength),
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = when (current) {
                    0 -> stringResource(R.string.vibration_off)
                    1 -> stringResource(R.string.vibration_low)
                    else -> stringResource(R.string.vibration_high)
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun NotificationPermissionRow(
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .width(140.dp)
                .padding(vertical = 6.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_notification_permission),
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = stringResource(R.string.settings_notification_action_allow),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun BatteryOptimizationRow(
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val helper = remember { com.pomotick.system.BatteryOptimizationHelper(context) }
    val isOptimized = remember { mutableStateOf(!helper.isIgnoringBatteryOptimizations()) }

    // Re-check on resume so the row updates when user returns from system settings
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isOptimized.value = !helper.isIgnoringBatteryOptimizations()
    }

    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .width(140.dp)
                .padding(vertical = 6.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_battery_optimization),
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = if (isOptimized.value)
                    stringResource(R.string.settings_battery_action_grant)
                else
                    stringResource(R.string.settings_battery_granted),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isOptimized.value)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun RingtonRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .width(140.dp)
                .padding(vertical = 6.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_ringtone_enabled),
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.secondary
                )
            )
        }
    }
}
