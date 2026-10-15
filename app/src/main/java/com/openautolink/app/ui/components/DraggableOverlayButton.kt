package com.openautolink.app.ui.components

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** One full-sized touch target; drag placement is independent of the subdued idle paint. */
@Composable
internal fun DraggableOverlayButton(
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    positionKey: String,
    geometry: OverlayButtonGeometry,
    modifier: Modifier = Modifier,
    onDragCompleted: () -> Unit = {},
    decoration: @Composable ((OverlayButtonPoint) -> Unit)? = null,
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("overlay_positions", Context.MODE_PRIVATE) }
    val store = remember(prefs, positionKey) { OverlayButtonPositionStore(prefs, positionKey) }
    var resetEpoch by remember(store) { mutableIntStateOf(0) }
    DisposableEffect(prefs, positionKey) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { preferences, changedKey ->
            // Launcher recovery can reset positions while this composition stays alive.
            // Ignore ordinary saves/migration: they must not invalidate an active gesture.
            if (changedKey == null ||
                (changedKey.startsWith("${positionKey}_") && !preferences.contains(changedKey))) {
                resetEpoch++
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // A resize/origin/scale change reprojects the preferred position synchronously.
    // Clamping is display-only; only a completed drag writes a new preference.
    var offset by remember(store, geometry, resetEpoch) {
        mutableStateOf(store.readOffset(geometry, OverlayButtonPoint(0f, 0f)))
    }
    var dragging by remember(store, geometry, resetEpoch) { mutableStateOf(false) }
    var suppressPointerClick by remember(store, geometry, resetEpoch) { mutableStateOf(false) }
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val currentOnDragCompleted by rememberUpdatedState(onDragCompleted)

    // This sibling uses exactly this frame's clamped position. It neither wraps
    // the target nor installs a full-screen pointer handler; the logo draws last.
    val safe = geometry.clampOffset(offset)
    decoration?.invoke(OverlayButtonPoint(geometry.anchorX + safe.x, geometry.anchorY + safe.y))

    FilledTonalButton(
        onClick = { if (!suppressPointerClick) onClick() },
        modifier = modifier
            .absoluteOffset {
                val safe = geometry.clampOffset(offset)
                IntOffset((geometry.anchorX + safe.x).roundToInt(), (geometry.anchorY + safe.y).roundToInt())
            }
            .size(with(density) { geometry.buttonWidth.toDp() }, with(density) { geometry.buttonHeight.toDp() })
            .pointerInput(geometry, store, resetEpoch) {
                // Button's inner clickable sees Main-pass up before the outer drag
                // detector. A stationary long press has no consumed move to cancel
                // that click. Keep suppression through Main, then retire it in Final:
                // neither the next physical tap nor an accessibility click is lost.
                try {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        suppressPointerClick = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                        } while (event.changes.any { it.pressed })
                        suppressPointerClick = false
                    }
                } finally {
                    suppressPointerClick = false
                }
            }
            .pointerInput(geometry, store, resetEpoch) {
                var moved = false
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        suppressPointerClick = true
                        dragging = true
                        moved = false
                    },
                    onDragEnd = {
                        if (moved) {
                            store.saveOffset(geometry, offset)
                            currentOnDragCompleted()
                        }
                        dragging = false
                    },
                    onDragCancel = {
                        dragging = false
                        offset = store.readOffset(geometry, OverlayButtonPoint(0f, 0f))
                    },
                ) { change, dragAmount ->
                    change.consume()
                    val next = geometry.clampOffset(OverlayButtonPoint(offset.x + dragAmount.x, offset.y + dragAmount.y))
                    moved = moved || next != offset
                    offset = next
                }
            },
        shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = colors.surface.copy(alpha = if (dragging) 0.85f else 0.35f),
            contentColor = colors.onSurface.copy(alpha = if (dragging) 1f else 0.65f),
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(painter = painter, contentDescription = contentDescription, modifier = Modifier.size(32.dp))
    }
}
