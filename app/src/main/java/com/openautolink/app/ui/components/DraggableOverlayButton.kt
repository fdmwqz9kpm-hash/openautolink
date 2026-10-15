package com.openautolink.app.ui.components

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Independently measured control. Its anchor is known BEFORE placement, never inferred from itself. */
@Composable
internal fun DraggableOverlayButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    positionKey: String,
    geometry: OverlayButtonGeometry,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("overlay_positions", Context.MODE_PRIVATE) }
    val store = remember(prefs, positionKey) { OverlayButtonPositionStore(prefs, positionKey) }
    // New constraints invalidate the gesture and reproject the persisted fraction
    // synchronously: there is no frame with a new anchor and an old offset.
    var offset by remember(store, geometry) {
        mutableStateOf(store.readOffset(geometry, OverlayButtonPoint(0f, 0f)))
    }
    var lastDragEndTime by remember { mutableStateOf(0L) }
    var wasDragged by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    FilledTonalButton(
        onClick = {
            val suppress = wasDragged && SystemClock.uptimeMillis() - lastDragEndTime < 300
            wasDragged = false
            if (!suppress) onClick()
        },
        modifier = modifier
            .absoluteOffset {
                val safe = geometry.clampOffset(offset)
                IntOffset((geometry.anchorX + safe.x).roundToInt(), (geometry.anchorY + safe.y).roundToInt())
            }
            .size(with(density) { geometry.buttonWidth.toDp() }, with(density) { geometry.buttonHeight.toDp() })
            .pointerInput(geometry, store) {
                detectDragGestures(
                    onDragStart = { wasDragged = false },
                    onDragEnd = {
                        if (wasDragged) {
                            lastDragEndTime = SystemClock.uptimeMillis()
                            store.saveOffset(geometry, offset)
                        }
                    },
                    onDragCancel = { wasDragged = false },
                ) { change, dragAmount ->
                    change.consume()
                    wasDragged = true
                    offset = geometry.clampOffset(OverlayButtonPoint(offset.x + dragAmount.x, offset.y + dragAmount.y))
                }
            },
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = containerColor, contentColor = tint),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
    }
}
