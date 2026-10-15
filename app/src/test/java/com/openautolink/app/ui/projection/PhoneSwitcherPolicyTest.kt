package com.openautolink.app.ui.projection

import com.openautolink.app.data.AppPreferences
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PhoneSwitcherPolicyTest {
    @Test fun onlyExplicitSharedWifiCarHotspotOffersSwitcher() {
        assertTrue(PhoneSwitcherPolicy.visible("hotspot", AppPreferences.CONNECTION_MODE_CAR_HOTSPOT))
        for (transport in listOf("wpp", "usb", "")) {
            assertFalse(PhoneSwitcherPolicy.visible(transport, AppPreferences.CONNECTION_MODE_CAR_HOTSPOT))
        }
        assertFalse(PhoneSwitcherPolicy.visible("hotspot", "phone_hotspot"))
    }

    @Test fun menuCallerDoesNotReadRetiredFloatingVisibilityPreference() {
        val relative = "src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt"
        val source = listOf(File(relative), File("app/$relative")).first { it.exists() }.readText()
        assertTrue(source.contains("PhoneSwitcherPolicy.visible(directTransport, connectionMode)"))
        assertFalse(source.contains("uiState.overlayPhoneSwitchButton"))
    }
}
