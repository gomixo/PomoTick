package com.pomotick.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

@Composable
fun TimerScreen(
    viewModel: TimerViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isRunning = state.runState == TimerRunState.RUNNING
    val planned = state.runtime?.plannedDurationMillis
        ?: when (state.selectedPhase) {
            TimerPhase.FOCUS -> state.settings.focusMinutes
            TimerPhase.SHORT_BREAK -> state.settings.shortBreakMinutes
            TimerPhase.LONG_BREAK -> state.settings.longBreakMinutes
        } * 60_000L
    val progress = if (planned > 0L && state.runtime != null) {
        ((planned - state.remainingMs).toFloat() / planned.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 34.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TimerDial(
            timeText = TimeFormatter.formatRemaining(state.remainingMs),
            progress = progress,
            showDot = isRunning,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(0.9f)
                .aspectRatio(1f)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TimerActionButton(
                onClick = {
                    if (isRunning) viewModel.onPause() else viewModel.onStartOrResume()
                },
                modifier = Modifier.weight(1f),
                primary = true,
                iconRes = if (isRunning) R.drawable.ic_action_pause else R.drawable.ic_action_play,
                contentDescription = if (isRunning) {
                    stringResource(R.string.action_pause)
                } else {
                    stringResource(R.string.action_start)
                },
                iconSize = 28.dp
            )
            TimerActionButton(
                onClick = { viewModel.onResetTimer() },
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.ic_action_reset,
                contentDescription = stringResource(R.string.action_reset)
            )
            TimerActionButton(
                onClick = { viewModel.onSwitchPhase() },
                enabled = !isRunning && state.runState != TimerRunState.RINGING,
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.ic_action_switch_phase,
                contentDescription = stringResource(R.string.action_switch_phase)
            )
        }
    }
}

@Composable
private fun TimerDial(
    timeText: String,
    progress: Float,
    showDot: Boolean,
    modifier: Modifier = Modifier
) {
    val trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val progressColor = MaterialTheme.colorScheme.primary
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 13.dp.toPx()
            val radius = (size.minDimension - stroke * 1.6f) / 2f
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
                drawCircle(color = progressColor, radius = stroke * 0.52f, center = dot)
            }
        }
        TimerText(timeText = timeText)
    }
}

@Composable
private fun TimerText(timeText: String) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(0.72f),
        contentAlignment = Alignment.Center
    ) {
        val ratio = if (timeText.length > 5) 0.16f else 0.22f
        val maxSize = if (timeText.length > 5) 34f else 42f
        val fontSize = minOf(maxWidth.value * ratio, maxSize).sp
        Text(
            text = timeText,
            modifier = Modifier.fillMaxWidth(),
            fontSize = fontSize,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun TimerActionButton(
    onClick: () -> Unit,
    iconRes: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    iconSize: androidx.compose.ui.unit.Dp = 28.dp
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(60.dp),
        contentPadding = PaddingValues(0.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
        colors = if (primary) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        }
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize)
        )
    }
}
