package com.pomotick.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pomotick.R
import com.pomotick.timer.TimeFormatter
import com.pomotick.timer.TimerPhase
import com.pomotick.timer.TimerRunState
import com.pomotick.ui.TimerViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * v0.2 §5 改版后的主计时屏。
 *
 * 布局：
 * - **大圆形进度环**（占满屏幕约 90%，四周留出安全距离）
 * - **中央显示剩余时间**（大字号）
 * - **时间下方一个操作 icon**（play/pause，根据 runState 切换）
 * - **点击中央** = 主操作（IDLE 开始 / RUNNING 暂停 / PAUSED 继续）
 * - **长按中央** = 弹出 2 个大按钮的菜单（重置时间 / 切换下一阶段 / 取消）
 *
 * 移除的旧元素：
 * - 3 个常驻 `TimerActionButton`（开始·暂停 / 重置 / 切换阶段）
 *
 * 关联的 QuickActionsScreen 文件保留但当前不引用——v0.2 不再需要快捷操作
 * 二级页面，所有"非主要操作"通过设置或长按菜单进入。
 */
@Composable
fun TimerScreen(
    viewModel: TimerViewModel,
    modifier: Modifier = Modifier
) {
    // v0.2 第四轮 P0 性能修复：TimerScreen 拆为两个独立订阅——
    //  - `baseState`：runtime / runState / phase / selectedPhase / settings
    //  - `remainingMs`：1Hz 倒计时
    // 这样 1Hz 倒计时不会让"非数字部分"（图标、按钮等）参与重组；
    // baseState 真正改变（状态机切换、设置变化）时才会走完整个重组。
    val base by viewModel.baseState.collectAsStateWithLifecycle()
    val remainingMs by viewModel.remainingMs.collectAsStateWithLifecycle()
    var showLongPressMenu by remember { mutableStateOf(false) }

    val isRunning = base.runState == TimerRunState.RUNNING
    val isPaused = base.runState == TimerRunState.PAUSED
    val isIdle = base.runState == TimerRunState.IDLE
    val canTap = isRunning || isPaused || isIdle

    val activePhase = base.phase ?: base.selectedPhase
    val isFocus = activePhase == TimerPhase.FOCUS
    val phaseColor = if (isFocus) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondary
    }

    val planned = base.runtime?.plannedDurationMillis
        ?: when (activePhase) {
            TimerPhase.FOCUS -> base.settings.focusMinutes
            TimerPhase.SHORT_BREAK -> base.settings.shortBreakMinutes
            TimerPhase.LONG_BREAK -> base.settings.longBreakMinutes
        } * 60_000L
    val progress = if (planned > 0L && base.runtime != null) {
        ((planned - remainingMs).toFloat() / planned.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TimerDial(
                timeText = TimeFormatter.formatRemaining(remainingMs),
                progress = progress,
                showDot = isRunning,
                progressColor = phaseColor,
                actionIconRes = when {
                    isRunning -> R.drawable.ic_action_pause
                    isPaused -> R.drawable.ic_action_play
                    else -> R.drawable.ic_action_play   // IDLE → 准备开始
                },
                actionIconDescription = when {
                    isRunning -> stringResource(R.string.action_pause)
                    isPaused -> stringResource(R.string.action_resume)
                    else -> stringResource(R.string.action_start)
                },
                onTap = {
                    if (!canTap) return@TimerDial
                    when (base.runState) {
                        TimerRunState.RUNNING -> viewModel.onPause()
                        TimerRunState.PAUSED -> viewModel.onResume()
                        TimerRunState.IDLE -> viewModel.onStartOrResume()
                        else -> Unit
                    }
                },
                onLongPress = {
                    if (canTap) showLongPressMenu = true
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(0.94f)
                    .aspectRatio(1f)
            )
        }

        if (showLongPressMenu) {
            ActionOverlay(
                onReset = {
                    showLongPressMenu = false
                    viewModel.onResetTimer()
                },
                onSwitchPhase = {
                    showLongPressMenu = false
                    viewModel.onSwitchPhase()
                },
                onDismiss = { showLongPressMenu = false }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimerDial(
    timeText: String,
    progress: Float,
    showDot: Boolean,
    progressColor: androidx.compose.ui.graphics.Color,
    actionIconRes: Int,
    actionIconDescription: String,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val trackColor = progressColor.copy(alpha = 0.12f)
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = rememberRipple(bounded = false, radius = 220.dp),
            onClick = onTap,
            onLongClick = onLongPress
        ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 18.dp.toPx()
            val radius = (size.minDimension - stroke) / 2.2f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = trackColor,
                radius = radius,
                center = center,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (progress > 0f) {
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            if (showDot) {
                val angle = Math.toRadians((progress * 360f - 90f).toDouble())
                val dot = Offset(
                    x = center.x + kotlin.math.cos(angle).toFloat() * radius,
                    y = center.y + kotlin.math.sin(angle).toFloat() * radius
                )
                drawCircle(color = progressColor, radius = stroke * 0.45f, center = dot)
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            TimerText(timeText = timeText, color = progressColor)
            Spacer(modifier = Modifier.height(4.dp))
            Icon(
                painter = painterResource(actionIconRes),
                contentDescription = actionIconDescription,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

@Composable
private fun TimerText(timeText: String, color: androidx.compose.ui.graphics.Color) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(0.78f),
        contentAlignment = Alignment.Center
    ) {
        val ratio = if (timeText.length > 5) 0.20f else 0.26f
        val maxSize = if (timeText.length > 5) 40f else 50f
        val fontSize = minOf(maxWidth.value * ratio, maxSize).sp
        Text(
            text = timeText,
            modifier = Modifier.fillMaxWidth(),
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * v0.2 §5 改版：替换原 AlertDialog。
 * 这是一个全屏半透明覆盖层，提供两个居中的大型操作按钮。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionOverlay(
    onReset: () -> Unit,
    onSwitchPhase: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.85f))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.menu_long_press_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            BigMenuButton(
                label = stringResource(R.string.menu_reset),
                iconRes = R.drawable.ic_action_reset,
                onClick = onReset,
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )

            BigMenuButton(
                label = stringResource(R.string.menu_switch_phase),
                iconRes = R.drawable.ic_action_switch_phase,
                onClick = onSwitchPhase,
                containerColor = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            androidx.compose.material3.IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
        }
    }

    // 拦截返回键以关闭覆盖层
    androidx.activity.compose.BackHandler(onBack = onDismiss)
}

@Composable
private fun BigMenuButton(
    label: String,
    iconRes: Int,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = label,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
