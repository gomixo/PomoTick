package com.pomotick.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pomotick.R
import com.pomotick.timer.TimeFormatter
import com.pomotick.ui.DailyFocus
import com.pomotick.ui.TimerViewModel
import com.pomotick.ui.components.BigButton

/**
 * v0.2 §8 改版后的统计屏。
 *
 * 布局（垂直滚动）：
 * ```
 * 1. 顶部：今日专注总时间 / 今日休息总时间 + 今日完成次数
 * 2. 今日 4 时段专注分布（4 行 + 短条形视觉块）
 * 3. 分割线
 * 4. 最近 7 天标题 + 7 天总专注
 * 5. 7 天每日专注（日期 + 时长 + 短条）
 * 6. 返回按钮
 * ```
 *
 * - 上滑自然暴露周统计
 * - 不引入任何图表库——短条用 [Canvas] 手绘
 * - 全部数据源 = Room 中的 `timer_sessions` 表（DAO 查询），**不依赖 UI 临时状态**
 */
@Composable
fun TodayStatsScreen(
    viewModel: TimerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stats by viewModel.statsState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300L)
        viewModel.refreshTodayStats()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ===== 页面标题 =====
        Text(
            text = stringResource(R.string.stats_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // ===== 今日总览卡片（专注 + 休息 + 完成次数一行） =====
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 今日专注
                SummaryTile(
                    label = stringResource(R.string.stats_today_focus),
                    value = TimeFormatter.formatDuration(stats.todayFocusMillis),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )

                // 竖线分隔
                val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(32.dp)
                        .padding(vertical = 4.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(color = dividerColor)
                    }
                }

                // 今日休息
                SummaryTile(
                    label = stringResource(R.string.stats_today_break),
                    value = TimeFormatter.formatDuration(stats.todayBreakMillis),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 今日完成次数
        Text(
            text = "${stringResource(R.string.stats_today_count)}: ${stats.todayCount}",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground
        )

        // ===== 今日 4 时段 =====
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.stats_buckets_title),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth()
        )
        val bucketMax = (stats.focusBuckets.maxOrNull() ?: 0L).coerceAtLeast(1L)
        val bucketLabels = listOf(
            stringResource(R.string.stats_bucket_dawn),
            stringResource(R.string.stats_bucket_morning),
            stringResource(R.string.stats_bucket_afternoon),
            stringResource(R.string.stats_bucket_evening)
        )
        stats.focusBuckets.forEachIndexed { idx, ms ->
            BucketRow(
                label = bucketLabels[idx],
                focusMillis = ms,
                maxMillis = bucketMax
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f),
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // ===== 最近 7 天 =====
        Text(
            text = stringResource(R.string.stats_weekly_title),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth()
        )

        val weeklyMax = (stats.weeklyFocus.maxOfOrNull { it.focusMillis } ?: 0L).coerceAtLeast(1L)
        val weeklyTotal = stats.weeklyFocus.sumOf { it.focusMillis }
        Text(
            text = "${stringResource(R.string.stats_weekly_total)}: ${
                TimeFormatter.formatDuration(weeklyTotal)
            }",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth()
        )

        if (stats.weeklyFocus.all { it.focusMillis == 0L }) {
            Text(
                text = stringResource(R.string.stats_weekly_empty),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            stats.weeklyFocus.forEach { day ->
                WeeklyRow(
                    day = day,
                    maxMillis = weeklyMax
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
private fun SummaryTile(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

/**
 * 单个时段行——左侧标签，中间短条，右侧时长。
 */
@Composable
private fun BucketRow(
    label: String,
    focusMillis: Long,
    maxMillis: Long
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth(0.22f)
        )
        MiniBar(
            fraction = (focusMillis.toFloat() / maxMillis.toFloat()).coerceIn(0f, 1f),
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
        )
        Text(
            text = TimeFormatter.formatDuration(focusMillis),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth(0.28f)
        )
    }
}

/**
 * 周列表的单日行——左侧日期，中间短条，右侧时长。
 */
@Composable
private fun WeeklyRow(
    day: DailyFocus,
    maxMillis: Long
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = day.label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth(0.22f)
        )
        MiniBar(
            fraction = (day.focusMillis.toFloat() / maxMillis.toFloat()).coerceIn(0f, 1f),
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
        )
        Text(
            text = TimeFormatter.formatDuration(day.focusMillis),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth(0.32f)
        )
    }
}

/**
 * 短条形视觉块——pill 形状，主色填充。
 *
 * 比例 `fraction` 为 0..1；背景为主色 15% 透明度，填充为完整主色。
 */
@Composable
private fun MiniBar(
    fraction: Float,
    modifier: Modifier = Modifier
) {
    val bg = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val fg = MaterialTheme.colorScheme.primary
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val corner = size.height / 2f
            drawRoundRect(
                color = bg,
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(corner, corner)
            )
            if (fraction > 0f) {
                drawRoundRect(
                    color = fg,
                    size = Size(size.width * fraction, size.height),
                    cornerRadius = CornerRadius(corner, corner)
                )
            }
        }
    }
}
