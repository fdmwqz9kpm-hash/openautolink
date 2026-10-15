package com.openautolink.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor

private data class OverlayWindowGeometry(
    val origin: Offset,
    val clippedBounds: Rect,
    val windowBounds: Rect,
)

/** Stable window origin of the safe-area child, not of a moving control. */
internal val LocalOverlaySafeAreaOrigin = compositionLocalOf { Offset.Zero }

/**
 * A controls-only viewport; does not change video measurement, scaling, or insets.
 *
 * Intersect in window coordinates BEFORE converting to parent-local coordinates. In
 * particular, a parent that already avoids the bars must not be inset a second time.
 * Visible Compose insets reclaim hidden chrome space and update reactively.
 * Cutouts remain excluded even in immersive mode.
 *
 * There is intentionally no full-screen pointer handler. Empty space remains available
 * to the projected app. Children receive only the visible safe area's constraints.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OverlaySafeArea(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    // These are physical left/right, not start/end; layoutDirection is used only by
    // the WindowInsets API to resolve any directional implementation.
    val leftInset = safeInsets.getLeft(density, layoutDirection)
    val topInset = safeInsets.getTop(density)
    val rightInset = safeInsets.getRight(density, layoutDirection)
    val bottomInset = safeInsets.getBottom(density)
    // Physical spacing must not grow with the OAL interface size.
    val marginPx = 2f * view.resources.displayMetrics.density
    var geometry by remember(view) { mutableStateOf<OverlayWindowGeometry?>(null) }

    Box(
        modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            // Observe ONLY the un-offset outer Box. Measuring the moving inner Box
            // would feed its own correction back into the next placement.
            val root = view.rootView
            val rootLocation = IntArray(2)
            root.getLocationInWindow(rootLocation)
            geometry = OverlayWindowGeometry(
                origin = coordinates.positionInWindow(),
                clippedBounds = coordinates.boundsInWindow(),
                windowBounds = Rect(
                    rootLocation[0].toFloat(), rootLocation[1].toFloat(),
                    (rootLocation[0] + root.width).toFloat(),
                    (rootLocation[1] + root.height).toFloat(),
                ),
            )
        },
        contentAlignment = AbsoluteAlignment.TopLeft,
    ) {
        val measured = geometry
        val bounds = measured?.let {
            overlaySafeContentBounds(
                outerOrigin = it.origin,
                clippedBoundsInWindow = it.clippedBounds,
                safeBoundsInWindow = Rect(
                    it.windowBounds.left + leftInset,
                    it.windowBounds.top + topInset,
                    it.windowBounds.right - rightInset,
                    it.windowBounds.bottom - bottomInset,
                ),
                edgeMarginPx = marginPx,
            )
        }
        // Until measured, or when completely clipped, do not create actionable
        // off-screen controls. Never substitute the larger, unclipped parent bounds.
        if (bounds != null && measured != null) {
            Box(
                Modifier
                    .absoluteOffset { IntOffset(bounds.left, bounds.top) }
                    .requiredSize(
                        with(density) { bounds.width.toDp() },
                        with(density) { bounds.height.toDp() },
                    )
                    .clipToBounds(),
                contentAlignment = AbsoluteAlignment.TopLeft,
            ) {
                CompositionLocalProvider(LocalOverlaySafeAreaOrigin provides
                    (measured.origin + Offset(bounds.left.toFloat(), bounds.top.toFloat()))) {
                    content()
                }
            }
        }
    }
}

/**
 * Returns an inward-rounded, physical parent-local pixel rectangle, or null when no
 * safe content remains. Pure geometry: no Android APIs, density, or RTL mirroring.
 * [clippedBoundsInWindow] must come from the OUTER container, not a repositioned child.
 */
internal fun overlaySafeContentBounds(
    outerOrigin: Offset,
    clippedBoundsInWindow: Rect,
    safeBoundsInWindow: Rect,
    edgeMarginPx: Float,
): IntRect? {
    require(edgeMarginPx.isFinite() && edgeMarginPx >= 0f)
    val left = maxOf(clippedBoundsInWindow.left, safeBoundsInWindow.left) + edgeMarginPx
    val top = maxOf(clippedBoundsInWindow.top, safeBoundsInWindow.top) + edgeMarginPx
    val right = minOf(clippedBoundsInWindow.right, safeBoundsInWindow.right) - edgeMarginPx
    val bottom = minOf(clippedBoundsInWindow.bottom, safeBoundsInWindow.bottom) - edgeMarginPx
    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
        !outerOrigin.x.isFinite() || !outerOrigin.y.isFinite() ||
        right <= left || bottom <= top
    ) return null

    val result = IntRect(
        ceil(left - outerOrigin.x).toInt(),
        ceil(top - outerOrigin.y).toInt(),
        floor(right - outerOrigin.x).toInt(),
        floor(bottom - outerOrigin.y).toInt(),
    )
    return result.takeIf { it.width > 0 && it.height > 0 }
}
