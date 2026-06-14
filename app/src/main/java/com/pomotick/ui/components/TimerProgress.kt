package com.pomotick.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pomotick.timer.TimerRuntimeState
import com.pomotick.timer.remainingMillis

/**
 * 水平进度条（横条而非圆环，更适合方形屏）。
 */
@Composable
fun TimerProgress(
    state: TimerRuntimeState,
    now: Long,
    modifier: Modifier = Modifier
) {
    val remaining = remainingMillis(now, state)
    val planned = state.plannedDurationMillis
    val elapsed = (planned - remaining).coerceIn(0L, planned)
    val progress = if (planned > 0L) (elapsed.toFloat() / planned.toFloat()) else 0f

    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )
}
