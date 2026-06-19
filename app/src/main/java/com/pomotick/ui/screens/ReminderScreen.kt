package com.pomotick.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pomotick.R
import com.pomotick.ui.TimerViewModel

/**
 * v0.2 P1 改版：RINGING 屏提供 2 个大按钮——
 *
 * 1. **"停止声震"**（secondary）—— 走 [TimerEvent.StopRingingOnly]，仅停铃声+震动，
 *    保持 RINGING 状态、启动 §4 等待窗口（30s → 3min → 1 次 15s 重复）。
 *    与通知"停止"Action 完全相同入口。
 *
 * 2. **"知道了"**（primary）—— 走 [TimerEvent.StopRingingAndPrepareNext]，
 *    停声震 + 写 COMPLETED session + 推进轮次 + 进入下一阶段。
 *    Engine 用 runtime 快照决定"下一阶段"——避免 ViewModel 计算绕开通知 Action。
 */
@Composable
fun ReminderScreen(
    viewModel: TimerViewModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.weight(0.6f))
        Icon(
            imageVector = Icons.Filled.Alarm,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(44.dp)
        )
        Spacer(modifier = Modifier.height(22.dp))
        Text(
            text = stringResource(R.string.reminder_stop_title),
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2
        )
        Spacer(modifier = Modifier.weight(0.7f))

        // 主按钮：知道了（推进阶段）
        ReminderButton(
            label = stringResource(R.string.action_know_it),
            onClick = { viewModel.onStopRinging() },
            primary = true
        )
        Spacer(modifier = Modifier.height(12.dp))
        // 次按钮：停止声震（仅停声震，保持 RINGING）
        ReminderButton(
            label = stringResource(R.string.action_stop_alarm_only),
            onClick = { viewModel.onStopRingingOnly() },
            primary = false
        )
        Spacer(modifier = Modifier.weight(0.4f))
    }
}

@Composable
private fun ReminderButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        shape = RoundedCornerShape(30.dp),
        colors = if (primary) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        },
        border = if (!primary) {
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f)
            )
        } else null
    ) {
        Text(
            text = label,
            fontSize = if (primary) 19.sp else 16.sp,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
