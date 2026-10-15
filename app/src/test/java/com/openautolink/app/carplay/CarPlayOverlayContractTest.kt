package com.openautolink.app.carplay

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source integration guards, NOT rendered Compose/device proof. */
class CarPlayOverlayContractTest {
    private fun source(path: String) = File("src/main/java/com/openautolink/app/$path").readText()

    @Test fun floatingControlsAreAboveStatsButBelowSettings() {
        val layer = source("carplay/CarPlayProjectionLayer.kt")
        assertTrue(layer.indexOf("StatsForNerdsOverlay(") < layer.indexOf("ProjectionOverlayControls("))
        val screen = source("ui/projection/ProjectionScreen.kt")
        assertTrue(screen.indexOf("VideoStatsOverlay(") < screen.indexOf("ProjectionOverlayControls("))
        assertTrue(screen.indexOf("ProjectionOverlayControls(") < screen.indexOf("// Settings overlay —"))
    }

    @Test fun carPlayUsesExistingFloatingControlsAndKeepsSurfaceOutsideOverlayVisibility() {
        val layer = source("carplay/CarPlayProjectionLayer.kt")
        assertTrue("Use the same controls as AA", layer.contains("ProjectionOverlayControls("))
        assertFalse(layer.contains("FilledTonalButton"))
        assertFalse(layer.contains("experimental manual connection"))
        assertFalse(layer.contains("Alignment.TopStart"))
        assertTrue(layer.indexOf("AndroidView(") < layer.indexOf("ProjectionOverlayControls("))
        assertTrue(layer.contains("CarPlayOverlayPolicy.forwardTouch(showSettings, showDiagnostics)"))
        val screen = source("ui/projection/ProjectionScreen.kt")
        assertTrue(screen.contains("ProjectionOverlayControls("))
        assertTrue(screen.contains("controls = uiState"))
        assertTrue(screen.contains("onReconnect = onReconnect"))
        val shared = source("ui/components/ProjectionOverlayControls.kt")
        assertTrue(shared.contains("DraggableOverlayButton("))
        assertTrue(shared.contains("OverlayControl(\"Settings\""))
        assertTrue(shared.contains("OverlayControl(\"Stats for nerds\""))
        assertTrue(shared.contains("OverlayControl(\"Reconnect\""))
        assertTrue("Settings precedes optional actions", shared.indexOf("OverlayControl(\"Settings\"") < shared.indexOf("if (onSwitchPhone != null)"))
        assertEquals("one movable logo, no individual floating controls", 1, Regex("DraggableOverlayButton\\(").findAll(shared).count())
        assertFalse(shared.contains("overlayRecoveryButton"))
        assertTrue("No unsupported phone chooser by default", shared.contains("onSwitchPhone: (() -> Unit)? = null"))
        assertTrue("No AA ignition simulation by default", shared.contains("onSimulateIgnition: (() -> Unit)? = null"))
    }
}
