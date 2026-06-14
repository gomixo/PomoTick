package com.pomotick.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.pomotick.ui.TimerViewModel
import com.pomotick.ui.components.BigButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 今日统计屏——完成数、专注总时长、最近完成时间。
 */
@Composable
fun TodayStatsScreen(
    viewModel: TimerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshTodayStats()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.stats_title),
            fontSize = 20.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 今日完成数
        StatRow(
            label = stringResource(R.string.stats_today_count),
            value = "${state.todayCount}"
        )

        // 今日专注总时长
        StatRow(
            label = stringResource(R.string.stats_today_focus),
            value = TimeFormatter.formatDuration(state.todayFocusMillis)
        )

        // 最近完成时间
        StatRow(
            label = stringResource(R.string.stats_latest),
            value = state.latestCompleted?.endedAtEpochMillis?.let {
                formatTime(it)
            } ?: stringResource(R.string.stats_none)
        )

        Spacer(modifier = Modifier.height(8.dp))

        BigButton(
            label = stringResource(R.string.action_back),
            onClick = onBack,
            primary = false
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = label, fontSize = 12.sp)
        Text(text = value, fontSize = 24.sp)
    }
}

private fun formatTime(epochMillis: Long): String {
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    return fmt.format(Date(epochMillis))
}
