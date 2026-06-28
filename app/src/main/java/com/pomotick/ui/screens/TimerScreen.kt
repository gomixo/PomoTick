package com.pomotick.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
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
import com.pomotick.ui.components.KeepScreenOn
import com.pomotick.ui.components.watchSafeDiameter
import com.pomotick.ui.theme.LocalExtendedColors

/**
 * Timer screen — sized for the OPPO Watch 4 Pro round visible area.
 *
 * Layout (top -> bottom inside the safe-area circle):
 *  1. Timer ring  (~125dp diameter on OPPO Watch 4 Pro)
 *      - Thick stroke (~10.6dp) for watch-bezel quality
 *      - Frosted glass inner disc with phase label / countdown / icon
 *      - NO outer glow layers (clean, bold ring)
 *  2. Cycle indicator row (dots + "2/4" text in a single Row)
 *  3. (Overlay) Long-press action menu
 */
@Composable
fun TimerScreen(
    viewModel: TimerViewModel,
    isVisible: Boolean = true,
    modifier: Modifier = Modifier
) {
    val base by viewModel.baseState.collectAsStateWithLifecycle()
    val remainingMs by viewModel.remainingMs.collectAsStateWithLifecycle()
    var showLongPressMenu by remember { mutableStateOf(false) }

    val isRunning = base.runState == TimerRunState.RUNNING
    val isPaused = base.runState == TimerRunState.PAUSED
    val isIdle = base.runState == TimerRunState.IDLE
    val canTap = isRunning || isPaused || isIdle

    val activePhase = base.phase ?: base.selectedPhase
    KeepScreenOn(enabled = isVisible && activePhase == TimerPhase.FOCUS && isRunning)

    val phaseColor = when (activePhase) {
        TimerPhase.FOCUS -> MaterialTheme.colorScheme.primary
        TimerPhase.SHORT_BREAK -> MaterialTheme.colorScheme.secondary
        TimerPhase.LONG_BREAK -> MaterialTheme.colorScheme.tertiary
    }
    val phaseLabel = when (activePhase) {
        TimerPhase.FOCUS -> stringResource(R.string.phase_focus)
        TimerPhase.SHORT_BREAK -> stringResource(R.string.phase_short_break)
        TimerPhase.LONG_BREAK -> stringResource(R.string.phase_long_break)
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

    val totalCycles = base.settings.focusCyclesBeforeLongBreak.coerceAtLeast(1)
    val rawCycleIndex = base.runtime?.cyclePositionAtStart ?: base.settings.cyclePosition
    // 休息阶段轮次指示器不提前递增：
    // 专注 N 完成后 cyclePosition 已 +1，但休息期间仍应显示 "N/total"，
    // 直到下一次专注开始才显示 "N+1/total"。
    val activeCycleIndex = if (activePhase == TimerPhase.SHORT_BREAK
        || activePhase == TimerPhase.LONG_BREAK) {
        (rawCycleIndex - 1).coerceAtLeast(0)
    } else {
        rawCycleIndex
    }
    val cycleText = "${(activeCycleIndex + 1).coerceAtMost(totalCycles)}/$totalCycles"

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val diameter = watchSafeDiameter()
        // Ring diameter: 76% of safe diameter (user confirmed this size is fine)
        val ringDiameter = diameter * 0.76f
        // Thickened stroke: ~8.5% of ring diameter (was 5.5%) — watch-bezel quality
        val ringStroke = ringDiameter * 0.085f

        // Fixed sizes (no longer proportional — meets readability/tap floors)
        val timeFontSize = 28.sp
        val phaseFontSize = 12.sp
        val iconSize = 24.dp
        val cycleTextSize = 13.sp
        val dotSize = 7.dp
        val dotSpacing = 8.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            TimerDial(
                timeText = TimeFormatter.formatRemaining(remainingMs),
                progress = progress,
                showDot = isRunning,
                progressColor = phaseColor,
                phaseLabel = phaseLabel,
                actionIconRes = when {
                    isRunning -> R.drawable.ic_action_pause
                    isPaused -> R.drawable.ic_action_play
                    else -> R.drawable.ic_action_play
                },
                actionIconDescription = when {
                    isRunning -> stringResource(R.string.action_pause)
                    isPaused -> stringResource(R.string.action_resume)
                    else -> stringResource(R.string.action_start)
                },
                ringDiameter = ringDiameter,
                ringStroke = ringStroke,
                timeFontSize = timeFontSize,
                phaseFontSize = phaseFontSize,
                iconSize = iconSize,
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
                }
            )

            // Merged cycle indicator: dots + "2/4" text in a single Row
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(dotSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(totalCycles) { index ->
                    val filled = index <= activeCycleIndex
                    Box(
                        modifier = Modifier
                            .size(dotSize)
                            .background(
                                color = if (filled) phaseColor
                                else MaterialTheme.colorScheme.surfaceVariant,
                                shape = CircleShape
                            )
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = cycleText,
                    fontSize = cycleTextSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1
                )
            }
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
    progressColor: Color,
    phaseLabel: String,
    actionIconRes: Int,
    actionIconDescription: String,
    ringDiameter: androidx.compose.ui.unit.Dp,
    ringStroke: androidx.compose.ui.unit.Dp,
    timeFontSize: androidx.compose.ui.unit.TextUnit,
    phaseFontSize: androidx.compose.ui.unit.TextUnit,
    iconSize: androidx.compose.ui.unit.Dp,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val radius = ringDiameter / 2f
    val extendedColors = LocalExtendedColors.current

    Box(
        modifier = Modifier
            .size(ringDiameter)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = rememberRipple(bounded = false, radius = ringDiameter / 2f),
                onClick = onTap,
                onLongClick = onLongPress
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radiusPx = radius.toPx()
            val strokePx = ringStroke.toPx()

            // Track ring (no outer glow — clean and bold)
            drawCircle(
                color = progressColor.copy(alpha = 0.18f),
                radius = radiusPx,
                center = center,
                style = Stroke(width = strokePx)
            )

            // Progress arc
            if (progress > 0f) {
                withTransform({ rotate(degrees = -90f, pivot = center) }) {
                    drawArc(
                        color = progressColor,
                        startAngle = 0f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = Offset(center.x - radiusPx, center.y - radiusPx),
                        size = Size(radiusPx * 2f, radiusPx * 2f),
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                }

                // Single endpoint dot (no double-layer glow)
                if (showDot) {
                    val angle = Math.toRadians((progress * 360f - 90f).toDouble())
                    val dotCenter = Offset(
                        x = center.x + kotlin.math.cos(angle).toFloat() * radiusPx,
                        y = center.y + kotlin.math.sin(angle).toFloat() * radiusPx
                    )
                    drawCircle(
                        color = progressColor,
                        radius = strokePx * 0.35f,
                        center = dotCenter
                    )
                }
            }
        }

        // Frosted glass inner disc (user-requested: real watch-face feel)
        val innerDiameter = ringDiameter - ringStroke * 2f
        val innerRadiusPx = with(LocalDensity.current) { (innerDiameter / 2f).toPx() }
        Box(
            modifier = Modifier
                .size(innerDiameter)
                .background(
                    color = extendedColors.glassBackground,
                    shape = CircleShape
                )
                .border(
                    width = 1.dp,
                    color = extendedColors.glassBorder,
                    shape = CircleShape
                )
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.06f),
                            Color.White.copy(alpha = 0.02f),
                            Color.Transparent
                        ),
                        radius = innerRadiusPx
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = phaseLabel,
                    fontSize = phaseFontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = progressColor.copy(alpha = 0.8f),
                    letterSpacing = 0.08.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = timeText,
                    fontSize = timeFontSize,
                    fontWeight = FontWeight.Medium,
                    color = progressColor,
                    letterSpacing = (-0.02).sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(modifier = Modifier.height(4.dp))
                Icon(
                    painter = painterResource(actionIconRes),
                    contentDescription = actionIconDescription,
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

/**
 * v0.2 §5 改版：全屏操作覆盖层。
 * Content constrained to safe-area diameter to avoid corner clipping.
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
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val safeDiameter = watchSafeDiameter()
            Column(
                modifier = Modifier
                    .width(safeDiameter)
                    .padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.menu_long_press_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )

                BigMenuButton(
                    label = stringResource(R.string.menu_reset),
                    iconRes = R.drawable.ic_action_reset,
                    onClick = onReset,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onBackground
                )

                BigMenuButton(
                    label = stringResource(R.string.menu_switch_phase),
                    iconRes = R.drawable.ic_action_switch_phase,
                    onClick = onSwitchPhase,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    isPrimary = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = rememberRipple(bounded = true, radius = 24.dp),
                            onClick = onDismiss
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_back),
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }

    androidx.activity.compose.BackHandler(onBack = onDismiss)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BigMenuButton(
    label: String,
    iconRes: Int,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    isPrimary: Boolean = false
) {
    val borderColor = if (isPrimary) Color.Transparent else Color.White.copy(alpha = 0.10f)
    Box(
        modifier = Modifier
            .width(140.dp)
            .height(52.dp)
            .background(containerColor, RoundedCornerShape(16.dp))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(bounded = true, radius = 100.dp),
                onClick = onClick
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = contentColor
            )
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
