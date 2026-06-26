package com.pomotick.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pomotick.R
import com.pomotick.timer.TimeFormatter
import com.pomotick.ui.DailyFocus
import com.pomotick.ui.TimerViewModel

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
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ===== 页面标题 =====
        Text(
            text = stringResource(R.string.stats_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // ===== 今日总览卡片（专注 + 休息 + 完成次数） =====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(16.dp)
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(32.dp)
                            .background(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                    )

                    // 今日休息
                    SummaryTile(
                        label = stringResource(R.string.stats_today_break),
                        value = TimeFormatter.formatDuration(stats.todayBreakMillis),
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 今日完成次数
                Text(
                    text = "${stringResource(R.string.stats_today_count)}: ${stats.todayCount}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                )
            }
        }

        // ===== 今日 4 时段 =====
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.stats_buckets_title),
            fontSize = 13.sp,
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
            modifier = Modifier.padding(vertical = 2.dp)
        )

        // ===== 最近 7 天 =====
        Text(
            text = stringResource(R.string.stats_weekly_title),
            fontSize = 13.sp,
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

        Spacer(modifier = Modifier.height(2.dp))

        // ===== 返回按钮 =====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(999.dp)
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(999.dp)
                )
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.action_back),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
private fun SummaryTile(
    label: String,
    value: String,
    color: Color,
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
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
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
 * 短条形视觉块——pill 形状，主色渐变填充。
 *
 * 比例 `fraction` 为 0..1；背景为 surface 30% 透明度，填充为 primary 渐变。
 */
@Composable
private fun MiniBar(
    fraction: Float,
    modifier: Modifier = Modifier
) {
    val bg = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
    val gradient = Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
    )
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
                    brush = gradient,
                    size = Size(size.width * fraction, size.height),
                    cornerRadius = CornerRadius(corner, corner)
                )
            }
        }
    }
}
