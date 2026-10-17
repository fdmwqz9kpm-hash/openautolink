package com.openautolink.app.ui.settings

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** UI wiring contract; physical menu actions are covered by rendered overlay tests. */
class SingleOverlayMenuSettingsTest {
    @Test fun `settings explain one menu and do not offer obsolete floating toggles`() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        val source = File(root, "app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt").readText()
        assertTrue(source.contains("OAL controls menu"))
        assertTrue(source.contains("Long-press"))
        listOf("overlayStatsToggle", "overlayPhoneSwitchToggle", "overlayReconnectToggle",
            "floating Upload button", "floating power button").forEach {
            assertFalse("Obsolete setting: $it", source.contains(it))
        }
        assertTrue(source.contains("uiState.logUploadEnabled"))
        assertTrue(source.contains("uiState.simulateIgnitionButton"))
    }
}
