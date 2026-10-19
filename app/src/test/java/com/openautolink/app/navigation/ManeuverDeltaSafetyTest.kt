package com.openautolink.app.navigation

import com.openautolink.app.transport.ControlMessage
import org.junit.Assert.*
import org.junit.Test

class ManeuverDeltaSafetyTest {
    private fun delta(maneuver: String? = null, image: String? = null, distance: Int? = null, exit: Int? = null) =
        ControlMessage.NavState(maneuver = maneuver, navImageBase64 = image, distanceMeters = distance,
            road = null, etaSeconds = null, roundaboutExitNumber = exit)
    @Test fun newTurnWithoutImageClearsOldBitmapEvenWhenTypeRepeats() {
        val display = NavigationDisplayImpl()
        display.onNavState(delta("turn_left", "old", exit = 3))
        display.onNavState(delta("turn_left"))
        assertNull(display.currentManeuver.value!!.navImageBase64)
        assertNull(display.currentManeuver.value!!.roundaboutExitNumber)
    }
    @Test fun distanceOnlyDeltaPreservesBitmapAndOrdinal() {
        val display = NavigationDisplayImpl()
        display.onNavState(delta("roundabout_enter_and_exit_cw", "current", exit = 3))
        display.onNavState(delta(distance = 100))
        assertEquals("current", display.currentManeuver.value!!.navImageBase64)
        assertEquals(3, display.currentManeuver.value!!.roundaboutExitNumber)
    }
}
