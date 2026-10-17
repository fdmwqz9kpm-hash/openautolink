package com.openautolink.app.ui.components

import org.junit.Assert.*
import org.junit.Test

class OverlayButtonPositionTest {
    @Test fun `non-finite saved deltas recover to a finite reachable rectangle`() {
        val geometry = OverlayButtonGeometry(1200f, 700f, 1120f, 580f, 64f, 56f)
        val offset = geometry.clampOffset(OverlayButtonPoint(Float.NaN, Float.POSITIVE_INFINITY))
        assertTrue("saved NaN cannot be rendered or roundToInt", offset.x.isFinite())
        assertTrue(offset.y.isFinite())
    }

    @Test fun `full rectangle is bounded at every drag edge after resize and button size changes`() {
        for (geometry in listOf(
            OverlayButtonGeometry(1900f, 1100f, 1810f, 740f, 64f, 56f),
            OverlayButtonGeometry(1500f, 850f, 1410f, 480f, 80f, 72f),
            OverlayButtonGeometry(700f, 1200f, 610f, 930f, 64f, 56f),
        )) {
            for (x in listOf(-5000f, 0f, 5000f)) for (y in listOf(-5000f, 0f, 5000f)) {
                val offset = geometry.clampOffset(OverlayButtonPoint(x, y))
                val left = geometry.anchorX + offset.x
                val top = geometry.anchorY + offset.y
                assertTrue(left >= 0f && top >= 0f)
                assertTrue(left + geometry.buttonWidth <= geometry.parentWidth)
                assertTrue(top + geometry.buttonHeight <= geometry.parentHeight)
            }
        }
    }

    @Test fun `parent smaller than button uses origin without inverted ranges`() {
        val geometry = OverlayButtonGeometry(30f, 20f, -50f, -40f, 64f, 56f)
        assertEquals(OverlayButtonPoint(50f, 40f), geometry.clampOffset(OverlayButtonPoint(-900f, 900f)))
    }

    @Test fun `fraction round trip retains reachable custom drag`() {
        val geometry = OverlayButtonGeometry(1900f, 1100f, 1810f, 740f, 64f, 56f)
        val original = OverlayButtonPoint(-901f, -431f)
        val restored = geometry.offsetFromFraction(geometry.fractionFromOffset(original))
        assertEquals(original.x, restored.x, 0.001f)
        assertEquals(original.y, restored.y, 0.001f)
    }

    /** Supplemental source wiring guard; rectangle/persistence behavior is tested above. */
    @Test fun `single AA CarPlay logo uses measured geometry and long press without fixed recovery`() {
        val controls = java.io.File("src/main/java/com/openautolink/app/ui/components/ProjectionOverlayControls.kt").readText()
        assertEquals(1, Regex("DraggableOverlayButton\\(").findAll(controls).count())
        assertTrue(controls.contains("BoxWithConstraints("))
        assertTrue(controls.contains("LocalOverlaySafeAreaOrigin.current"))
        assertTrue(controls.contains("R.drawable.ic_oal_glyph"))
        assertFalse(controls.contains("overlayRecoveryButton"))
        assertFalse(controls.contains("padding(top = 56.dp)"))
        for (legacyToggle in listOf("overlayStatsButton", "overlayPhoneSwitchButton", "overlayReconnectButton")) {
            assertFalse("menu must not hide useful actions behind $legacyToggle", controls.contains("uiState.$legacyToggle"))
        }
        assertTrue("ignition simulation retains its maintainer opt-in",
            controls.contains("onSimulateIgnition != null && uiState.simulateIgnitionButton"))
        val button = java.io.File("src/main/java/com/openautolink/app/ui/components/DraggableOverlayButton.kt").readText()
        assertTrue(button.contains(".absoluteOffset"))
        assertTrue(button.contains(".pointerInput(geometry, store, resetEpoch)"))
        assertTrue(button.contains("detectDragGesturesAfterLongPress"))
        assertTrue(button.contains("CircleShape"))
        assertFalse(button.contains(".pointerInput(Unit)"))
        assertFalse("never infer anchor from offset coordinates", button.contains("positionInRoot"))
        assertTrue(button.contains("remember(store, geometry, resetEpoch)"))
        assertTrue(button.contains("store.readOffset(geometry"))
        assertTrue("listen for external reset", button.contains("registerOnSharedPreferenceChangeListener"))
        assertTrue("ordinary writes must not restart active drags", button.contains("!preferences.contains(changedKey)"))
        assertFalse("empty projection space must not intercept maps", controls.contains("pointerInput"))
    }

    @Test fun `legacy far-left upload remains wholly inside smaller system UI parent`() {
        val geometry = OverlayButtonGeometry(1200f, 700f, 1120f, 580f, 64f, 56f)
        val offset = geometry.clampOffset(OverlayButtonPoint(-1500f, 0f))
        assertEquals("button left must be inside its actual parent", 0f, geometry.anchorX + offset.x, 0f)
    }
}
