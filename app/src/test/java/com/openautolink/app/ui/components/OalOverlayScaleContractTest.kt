package com.openautolink.app.ui.components

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Integration boundary only; scoped density and actual scaled controls have rendered tests. */
class OalOverlayScaleContractTest {
    @Test fun `scale belongs to OAL controls and stats never the projected viewport`() {
        val root = File("src/main/java/com/openautolink/app")
        for (name in listOf("ui/components/ProjectionOverlayControls.kt", "ui/components/StatsForNerdsOverlay.kt")) {
            assertTrue("$name opts into OAL scale", root.resolve(name).readText().contains("OalUiScale {"))
        }
        for (name in listOf("ui/projection/ProjectionViewport.kt", "carplay/CarPlayProjectionLayer.kt")) {
            assertFalse("$name video coordinates must not be scaled", root.resolve(name).readText().contains("OalUiScale {"))
        }
    }
}
