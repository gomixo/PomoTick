package com.pomotick.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import com.pomotick.timer.ResponseAction
import com.pomotick.ui.TimerViewModel
import com.pomotick.ui.components.BigButton

/**
 * 提醒响应屏（RINGING 状态显示）。
 *
 * 三个动作：
 * - 知道了：结束当前 session
 * - 开始休息：进入休息阶段
 * - 继续专注：延长 5 分钟
 */
@Composable
fun ReminderScreen(
    viewModel: TimerViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = when (state.phase) {
                com.pomotick.timer.TimerPhase.FOCUS -> stringResource(R.string.reminder_focus_done)
                com.pomotick.timer.TimerPhase.SHORT_BREAK -> stringResource(R.string.reminder_break_done)
                com.pomotick.timer.TimerPhase.LONG_BREAK -> stringResource(R.string.reminder_break_done)
                null -> stringResource(R.string.reminder_done_default)
            },
            fontSize = 20.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        BigButton(
            label = stringResource(R.string.action_know_it),
            onClick = {
                viewModel.onRespond(ResponseAction.KnowIt)
            }
        )

        BigButton(
            label = stringResource(R.string.action_start_break),
            onClick = {
                viewModel.onRespond(ResponseAction.StartBreak)
            },
            primary = false
        )

        BigButton(
            label = stringResource(R.string.action_continue_focus),
            onClick = {
                viewModel.onRespond(ResponseAction.ContinueFocus)
            },
            primary = false
        )
    }
}
