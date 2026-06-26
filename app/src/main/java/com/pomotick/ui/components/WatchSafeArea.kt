package com.pomotick.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Wear OS safe-area helpers for the OPPO Watch 4 Pro (OWW221) primary target.
 *
 * The device panel is 378 × 496 px (≈ 189 × 248 dp at xhdpi) but the visible
 * area is a round area inscribed in that rectangle (C 型 3D 玻璃盖板). The
 * `WatchSafeArea` composable gives content a square box that fits inside the
 * round visible area so the corners don't get clipped.
 *
 * Per `AGENTS.md` §"UI Principles", never assume a fixed canvas (the original
 * 410×410 design canvas was wrong for this device).
 */

/** Inset on every side of the inscribed circle (and the wrapping `WatchSafeArea` box). */
val SafeAreaInset: Dp = 12.dp

/** Default diameter when nothing is measured yet (used inside Compose only as fallback). */
private val FallbackDiameter: Dp = 200.dp

/**
 * Compute the safe diameter for the current `BoxWithConstraints` scope.
 *
 * Usage:
 * ```
 * BoxWithConstraints {
 *     val diameter = watchSafeDiameter()
 * }
 * ```
 */
fun BoxWithConstraintsScope.watchSafeDiameter(): Dp {
    // kotlin.math.min has no Dp overload, so pick manually.
    val side = if (maxWidth < maxHeight) maxWidth else maxHeight
    val computed = side - SafeAreaInset * 2
    return if (computed > 0.dp) computed else FallbackDiameter
}

/**
 * Center a square content box of [watchSafeDiameter] size inside the parent.
 * Anything inside this box is guaranteed to be inside the round visible area.
 */
@Composable
fun WatchSafeArea(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val diameter = watchSafeDiameter()
            Box(
                modifier = Modifier.size(diameter),
                contentAlignment = Alignment.Center
            ) {
                content()
            }
        }
    }
}