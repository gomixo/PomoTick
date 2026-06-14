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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pomotick.R
import com.pomotick.timer.TimeFormatter
import com.pomotick.timer.TimerRunState
import com.pomotick.ui.TimerViewModel
import com.pomotick.ui.components.BigButton
import com.pomotick.ui.components.TimerProgress

/**
 * 主倒计时屏（IDLE / RUNNING / PAUSED）。
 */
@Composable
fun TimerScreen(
    viewModel: TimerViewModel,
    onNavigateToQuickActions: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToStats: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // 阶段名
        Text(
            text = state.phase?.let { stringResource(phaseLabel(it)) } ?: stringResource(R.string.app_name),
            fontSize = 18.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 倒计时大字
        Text(
            text = if (state.runtime == null) {
                TimeFormatter.formatRemaining(state.settings.focusMinutes * 60_000L)
            } else {
                TimeFormatter.formatRemaining(state.remainingMs)
            },
            fontSize = 56.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 进度条
        if (state.runtime != null) {
            TimerProgress(
                state = state.runtime!!,
                now = System.currentTimeMillis(),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 主按钮
        when (state.runState) {
            TimerRunState.IDLE -> {
                BigButton(label = stringResource(R.string.action_start), onClick = { viewModel.onStartFocus() })
            }
            TimerRunState.RUNNING -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BigButton(
                        label = stringResource(R.string.action_pause),
                        onClick = { viewModel.onPause() },
                        modifier = Modifier.weight(1f)
                    )
                    BigButton(
                        label = stringResource(R.string.action_more),
                        onClick = onNavigateToQuickActions,
                        modifier = Modifier.weight(1f),
                        primary = false
                    )
                }
            }
            TimerRunState.PAUSED -> {
                BigButton(label = stringResource(R.string.action_resume), onClick = { viewModel.onResume() })
            }
            TimerRunState.RINGING -> {
                BigButton(label = stringResource(R.string.action_respond), onClick = onNavigateToQuickActions)
            }
            TimerRunState.FINISHED -> {
                BigButton(label = stringResource(R.string.action_start), onClick = { viewModel.onStartFocus() })
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 次入口
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            BigButton(
                label = stringResource(R.string.action_settings),
                onClick = onNavigateToSettings,
                modifier = Modifier.weight(1f),
                primary = false
            )
            BigButton(
                label = stringResource(R.string.action_stats),
                onClick = {
                    viewModel.refreshTodayStats()
                    onNavigateToStats()
                },
                modifier = Modifier.weight(1f),
                primary = false
            )
        }
    }
}

private fun phaseLabel(phase: com.pomotick.timer.TimerPhase): Int = when (phase) {
    com.pomotick.timer.TimerPhase.FOCUS -> R.string.phase_focus
    com.pomotick.timer.TimerPhase.SHORT_BREAK -> R.string.phase_short_break
    com.pomotick.timer.TimerPhase.LONG_BREAK -> R.string.phase_long_break
}
