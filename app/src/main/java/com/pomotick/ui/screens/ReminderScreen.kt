package com.pomotick.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pomotick.R
import com.pomotick.timer.TimerPhase
import com.pomotick.ui.TimerViewModel
import kotlin.math.min

/**
 * RINGING screen — sized for the OPPO Watch 4 Pro round visible area.
 *
 * Layout (top → bottom inside the safe-area circle):
 *  1. Outlined checkmark (20dp) + "Focus Done!" title — same row
 *  2. Subtitle "当前计时已完成。"
 *  3. Primary "知道了" button (48dp)
 *  4. Secondary "停止声震" button (44dp)
 */
@Composable
fun ReminderScreen(
    viewModel: TimerViewModel,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context as? android.app.Activity
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Determine which phase just completed for the title
    val base by viewModel.baseState.collectAsStateWithLifecycle()
    val activePhase = base.phase ?: base.selectedPhase
    val reminderTitle = when (activePhase) {
        TimerPhase.FOCUS -> stringResource(R.string.reminder_focus_done)
        TimerPhase.SHORT_BREAK, TimerPhase.LONG_BREAK -> stringResource(R.string.reminder_break_done)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val sidePx = with(density) {
            min(maxWidth.toPx(), maxHeight.toPx())
        }
        val centerPx = Offset(
            x = with(density) { maxWidth.toPx() } / 2f,
            y = with(density) { maxHeight.toPx() } / 2f
        )

        // Subtle radial glow — radius constrained to safe area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.02f),
                            Color.Transparent
                        ),
                        center = centerPx,
                        radius = sidePx * 0.55f
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedCheckmark(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .scale(pulseScale)
                )

                Text(
                    text = reminderTitle,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Start,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.reminder_completed_body),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )

            Spacer(modifier = Modifier.height(14.dp))

            Column(
                modifier = Modifier.fillMaxWidth(0.9f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReminderButton(
                    label = stringResource(R.string.action_know_it),
                    onClick = { viewModel.onStopRinging() },
                    primary = true
                )
                ReminderButton(
                    label = stringResource(R.string.action_stop_alarm_only),
                    onClick = { viewModel.onStopRingingOnly() },
                    primary = false
                )
            }
        }
    }
}

@Composable
private fun OutlinedCheckmark(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val strokeCircle = 2.dp.toPx()
        val strokeCheck = 2.5f.dp.toPx()
        val w = size.width
        val h = size.height
        val radius = (w.coerceAtMost(h) - strokeCircle) / 2f

        drawCircle(
            color = color,
            radius = radius,
            center = center,
            style = Stroke(width = strokeCircle)
        )

        drawPath(
            path = Path().apply {
                moveTo(w * 0.25f, h * 0.52f)
                lineTo(w * 0.44f, h * 0.72f)
                lineTo(w * 0.75f, h * 0.32f)
            },
            color = color,
            style = Stroke(
                width = strokeCheck,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
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
            .height(if (primary) 48.dp else 44.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        shape = RoundedCornerShape(999.dp),
        colors = if (primary) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        border = if (!primary) {
            BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.surfaceVariant
            )
        } else null
    ) {
        Text(
            text = label,
            fontSize = if (primary) 15.sp else 13.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false
        )
    }
}