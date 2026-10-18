package com.openautolink.app.navigation

import com.openautolink.app.transport.ControlMessage
import org.junit.Assert.*
import org.junit.Test

class NavigationSnapshotCorrectionTest {
    private fun delta(maneuver:String?=null,distance:Int?=null)=ControlMessage.NavState(maneuver,distance,null,null)
    @Test fun newRawDistanceInvalidatesPreviousFormattedPairButRetainsArtwork() {
        val display=NavigationDisplayImpl()
        display.onNavState(delta("turn_left",500).copy(displayDistance="0.5",displayDistanceUnit="kilometers",navImageBase64="current"))
        display.onNavState(delta(distance=100))
        val state=display.currentManeuver.value!!
        assertNull(state.displayDistance)
        assertNull(state.displayDistanceUnit)
        assertEquals(100,state.distanceMeters)
        assertEquals("current",state.navImageBase64)
    }
    @Test fun explicitTurnDoesNotInheritPreviousInstructionEstimatesOrRoad() {
        val display=NavigationDisplayImpl()
        display.onNavState(delta("turn_left",500).copy(road="Old road",etaSeconds=40,displayDistance="0.5",displayDistanceUnit="kilometers"))
        display.onNavState(delta("turn_right"))
        val state=display.currentManeuver.value!!
        assertNull(state.roadName)
        assertNull(state.distanceMeters)
        assertNull(state.etaSeconds)
        assertNull(state.displayDistance)
        assertNull(state.displayDistanceUnit)
        display.onNavState(delta(distance=100))
        assertEquals(ManeuverType.TURN_RIGHT,display.currentManeuver.value!!.type)
        assertEquals(100,display.currentManeuver.value!!.distanceMeters)
    }
    @Test fun incompleteFormattedPairNeverCombinesWithOldPair() {
        val display=NavigationDisplayImpl()
        display.onNavState(delta("turn_left",500).copy(displayDistance="0.5",displayDistanceUnit="kilometers"))
        display.onNavState(delta().copy(displayDistance="100"))
        assertNull(display.currentManeuver.value!!.displayDistance)
        assertNull(display.currentManeuver.value!!.displayDistanceUnit)
    }
    @Test fun sourceAngleRetainedExactlyAcrossDistanceDeltaAndClearedOnNewTurn() {
        for(angle in listOf(-90,0,90,270,360,361)) {
            val display=NavigationDisplayImpl()
            display.onNavState(delta("roundabout_enter_and_exit_cw_with_angle").copy(roundaboutExitAngle=angle))
            // Reflection lets the RED test exercise missing propagation without compilation errors.
            fun angleOf():Any?=display.currentManeuver.value!!.javaClass.methods
                .firstOrNull { it.name=="getRoundaboutExitAngle" }?.invoke(display.currentManeuver.value)
            assertEquals(angle,angleOf())
            display.onNavState(delta(distance=100))
            assertEquals(angle,angleOf())
            display.onNavState(delta("turn_left"))
            assertNull(angleOf())
        }
    }
}
