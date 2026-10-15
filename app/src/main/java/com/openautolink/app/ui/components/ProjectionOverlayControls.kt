package com.openautolink.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.openautolink.app.ui.projection.ProjectionUiState
import com.openautolink.app.ui.projection.LogUploadState

private data class OverlayControl(
    val key: String, val tag: String, val label: String, val icon: ImageVector,
    val action: () -> Unit, val container: Color, val tint: Color,
)

/** Shared AA/CarPlay controls. Settings is unconditional; absent actions omit unsupported controls. */
@Composable
fun ProjectionOverlayControls(
    uiState: ProjectionUiState,
    onSettings: () -> Unit,
    onStats: () -> Unit,
    onReconnect: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onSwitchPhone: (() -> Unit)? = null,
    carHotspotSwitching: Boolean = false,
    onFileLogging: (() -> Unit)? = null,
    onUpload: (() -> Unit)? = null,
    onSimulateIgnition: (() -> Unit)? = null,
) = com.openautolink.app.ui.theme.OalUiScale {
    val colors = MaterialTheme.colorScheme
    val normal = colors.surface.copy(alpha = 0.5f)
    val controls = buildList {
        fun add(key: String, tag: String, label: String, icon: ImageVector, action: () -> Unit,
                container: Color = normal, tint: Color = colors.onSurface) {
            add(OverlayControl(key, tag, label, icon, action, container, tint))
        }
        add("overlay_settings", "settingsButton", "Settings", Icons.Default.Settings, onSettings)
        if (onSwitchPhone != null && uiState.overlayPhoneSwitchButton) {
            add("overlay_switch_phone", "switchPhoneButton", "Switch Phone", Icons.Default.PhoneAndroid, onSwitchPhone,
                if (carHotspotSwitching) colors.tertiary.copy(alpha = 0.7f) else normal,
                if (carHotspotSwitching) colors.onTertiary else colors.onSurface)
        }
        if (uiState.overlayStatsButton) {
            add("overlay_stats", "statsButton", "Stats for nerds", Icons.Default.Info, onStats,
                if (uiState.showStats) colors.primary.copy(alpha = 0.7f) else normal,
                if (uiState.showStats) colors.onPrimary else colors.onSurface)
        }
        if (onReconnect != null && uiState.overlayReconnectButton) {
            add("overlay_reconnect", "reconnectButton", "Reconnect", Icons.Default.Refresh, onReconnect)
        }
        if (onFileLogging != null && uiState.fileLoggingEnabled) {
            add("overlay_file_log", "fileLogButton", "File Logging", Icons.Default.FiberManualRecord, onFileLogging,
                if (uiState.fileLoggingActive) Color.Red.copy(alpha = 0.7f) else normal,
                if (uiState.fileLoggingActive) Color.White else colors.onSurface)
        }
        if (onUpload != null && uiState.uploadEnabled) {
            add("overlay_upload_logs", "uploadLogsButton", "Upload Logs",
                when (uiState.uploadState) {
                    LogUploadState.SUCCESS -> Icons.Default.CloudDone
                    LogUploadState.ERROR -> Icons.Default.CloudOff
                    else -> Icons.Default.CloudUpload
                }, onUpload,
                when (uiState.uploadState) {
                    LogUploadState.UPLOADING -> colors.tertiary.copy(alpha = 0.85f)
                    LogUploadState.SUCCESS -> Color(0xFF2E7D32).copy(alpha = 0.85f)
                    LogUploadState.ERROR -> Color.Red.copy(alpha = 0.8f)
                    LogUploadState.IDLE -> normal
                }, if (uiState.uploadState == LogUploadState.IDLE) colors.onSurface else Color.White)
        }
        if (onSimulateIgnition != null && uiState.simulateIgnitionButton) {
            add("overlay_simulate_ignition", "simulateIgnitionButton", "Simulate ignition cycle",
                Icons.Default.PowerSettingsNew, onSimulateIgnition)
        }
    }
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("overlay_positions", Context.MODE_PRIVATE) }
    var resetEpoch by remember { mutableIntStateOf(0) }
    var recoveryOpen by remember { mutableStateOf(false) }
    OverlaySafeArea(modifier) {
        // Reserve a separate, non-draggable recovery lane. No saved placement,
        // overlapping control, or canceled drag can cover this entry point.
        FilledTonalIconButton(
            onClick = { recoveryOpen = true },
            modifier = Modifier.align(Alignment.TopEnd).size(48.dp).testTag("overlayRecoveryButton"),
        ) {
            Icon(Icons.Default.Menu, contentDescription = "OAL controls and recovery")
        }
        // Each button receives full independent constraints, not a Column's remainder.
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = 56.dp),
            contentAlignment = androidx.compose.ui.AbsoluteAlignment.TopLeft) {
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            val density = androidx.compose.ui.platform.LocalDensity.current
            val buttonWidth = with(density) { 64.dp.toPx() }
            val buttonHeight = with(density) { 56.dp.toPx() }
            val gap = with(density) { 8.dp.toPx() }
            val rows = ((height + gap) / (buttonHeight + gap)).toInt().coerceAtLeast(1)
            val columns = ((width + gap) / (buttonWidth + gap)).toInt()
            // On unusually small windows use the fixed menu, not shrunken/stacked buttons.
            if (height >= buttonHeight && columns * rows >= controls.size) controls.forEachIndexed { index, control ->
                val slot = controls.lastIndex - index
                val geometry = OverlayButtonGeometry(width, height,
                    (width - buttonWidth - (slot / rows) * (buttonWidth + gap)).coerceAtLeast(0f),
                    (height - buttonHeight - (slot % rows) * (buttonHeight + gap)).coerceAtLeast(0f),
                    buttonWidth, buttonHeight)
                key(control.key, resetEpoch) {
                    DraggableOverlayButton(control.icon, control.label, control.action, control.key, geometry,
                        modifier = Modifier.testTag(control.tag), containerColor = control.container, tint = control.tint)
                }
            }
        }
    }
    if (recoveryOpen) {
        ProjectionOverlayRecoveryDialog(
            actions = controls.map { it.label to it.action },
            onDismiss = { recoveryOpen = false },
            onRestore = {
                OverlayButtonPositionStore.resetAll(prefs)
                resetEpoch++
                com.openautolink.app.diagnostics.DiagnosticLog.i("overlay", "Overlay positions restored from fixed recovery menu")
            },
        )
    }
}

/** Keep every element in one scroll viewport: fixed dialog chrome can consume a tiny window. */
@Composable
internal fun ProjectionOverlayRecoveryDialog(
    actions: List<Pair<String, () -> Unit>>,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
    maxHeight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
) = com.openautolink.app.ui.theme.OalUiScale {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        com.openautolink.app.ui.theme.OalUiScale {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth().testTag("overlayRecoveryScroll")
                .verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text("OAL controls", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                Text("Use a control here, or restore the floating buttons to their default positions.")
                actions.forEach { (label, action) ->
                    TextButton(
                        onClick = { onDismiss(); action() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("overlayRecoveryAction:$label"),
                    ) { Text(label) }
                }
                TextButton(
                    onClick = { onRestore(); onDismiss() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("overlayRecoveryAction:Restore button positions"),
                ) { Text("Restore button positions") }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("overlayRecoveryAction:Close"),
                ) { Text("Close") }
            }
        }
        }
    }
}
