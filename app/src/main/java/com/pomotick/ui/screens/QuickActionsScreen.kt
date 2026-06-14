package com.pomotick.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pomotick.R
import com.pomotick.ui.TimerViewModel
import com.pomotick.ui.components.BigButton

/**
 * 快捷操作屏——延长 5 分钟 / 提前结束 / 放弃。
 *
 * MVP 简化：放弃二次确认用一个 Compose 的"两步确认"——首次点击进入"再次点击以确认"模式。
 */
@Composable
fun QuickActionsScreen(
    viewModel: TimerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingAbandonConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.quick_actions_title),
            fontSize = 20.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        BigButton(
            label = stringResource(R.string.action_extend_5),
            onClick = {
                viewModel.onExtend5Min()
                onBack()
            }
        )

        BigButton(
            label = stringResource(R.string.action_finish_early),
            onClick = {
                viewModel.onFinishEarly()
                onBack()
            },
            primary = false
        )

        BigButton(
            label = if (pendingAbandonConfirm) {
                stringResource(R.string.abandon_confirm_yes)
            } else {
                stringResource(R.string.action_abandon)
            },
            onClick = {
                if (pendingAbandonConfirm) {
                    viewModel.onAbandon()
                    pendingAbandonConfirm = false
                    onBack()
                } else {
                    pendingAbandonConfirm = true
                }
            },
            primary = false
        )

        BigButton(
            label = stringResource(R.string.action_back),
            onClick = onBack,
            primary = false
        )
    }
}
