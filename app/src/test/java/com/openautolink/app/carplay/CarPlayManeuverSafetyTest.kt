package com.openautolink.app.carplay

import com.openautolink.carplaylab.metadata.CarPlayNavigationMetadata
import com.openautolink.app.cluster.buildManeuver
import com.openautolink.app.navigation.ManeuverType
import androidx.car.app.CarContext
import androidx.car.app.navigation.model.Maneuver
import android.content.res.Resources
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class CarPlayManeuverSafetyTest {
    private val context = mockk<CarContext>(relaxed = true).also { c ->
        val r = mockk<Resources>(relaxed = true)
        every { r.getResourceName(any()) } returns "com.openautolink.app:drawable/maneuver"
        every { c.resources } returns r
        every { c.packageName } returns "com.openautolink.app"
    }
    private fun state(wire: Int, side: Int?) = CarPlayClusterMetadataAdapter.maneuver(
        CarPlayNavigationMetadata(wire,side,350,"Road",120,1500))!!
    @Test fun genericRoundaboutsUseKnownTrafficSideWithoutOrdinal() {
        for ((wire, side, expected) in listOf(
            Triple(6,0,Maneuver.TYPE_ROUNDABOUT_ENTER_CCW), Triple(6,1,Maneuver.TYPE_ROUNDABOUT_ENTER_CW),
            Triple(7,0,Maneuver.TYPE_ROUNDABOUT_EXIT_CCW), Triple(7,1,Maneuver.TYPE_ROUNDABOUT_EXIT_CW))) {
            val model = buildManeuver(state(wire,side), context)
            assertEquals(expected, model.type)
            assertEquals(0, model.roundaboutExitNumber)
        }
        for (wire in listOf(6,7,19,255)) for (side in listOf(null,255)) {
            val mapped = state(wire,side)
            assertEquals(350, mapped.distanceMeters)
            assertEquals("Road", mapped.roadName)
            val model = buildManeuver(mapped,context)
            assertEquals(Maneuver.TYPE_UNKNOWN, model.type)
            if (wire == 6 || wire == 7) {
                assertEquals(if (wire == 6) ManeuverType.ROUNDABOUT_ENTER else ManeuverType.ROUNDABOUT_EXIT, mapped.type)
                assertNotNull("Known roundabout must retain a neutral icon without circulation", model.icon)
                assertEquals(0, model.roundaboutExitNumber)
            } else assertNull(model.icon)
        }
    }
    @Test fun numberedRoundaboutsHaveExactOrdinalOnBothSides() {
        for (wire in 28..46) for (side in listOf(0,1)) {
            val model = buildManeuver(state(wire,side),context)
            assertEquals(wire-27, model.roundaboutExitNumber)
        }
    }
    @Test fun completeCarPlayWireSetBuildsOnBothSidesAndUnknownBoundaryRetainsContext() {
        for (wire in 0..53) for (side in listOf(null,0,1,255)) {
            val mapped=state(wire,side)
            val model=buildManeuver(mapped,context)
            assertEquals("Road",mapped.roadName)
            assertEquals(350,mapped.distanceMeters)
            assertEquals(120L,mapped.timeToArrivalSeconds)
            if (wire in listOf(0,19,51)) {
                assertEquals(Maneuver.TYPE_UNKNOWN,model.type)
                assertNull(model.icon)
            }
        }
        for (side in listOf(null,0,1,255)) {
            assertEquals(Maneuver.TYPE_UNKNOWN,buildManeuver(state(255,side),context).type)
        }
    }
    @Test fun ferryAndDestinationVocabularyDoesNotCollapse() {
        for (wire in 15..17) assertEquals(ManeuverType.FERRY,state(wire,0).type)
        assertEquals(ManeuverType.DEPART,state(11,0).type)
        assertEquals(ManeuverType.DESTINATION_LEFT,state(24,0).type)
        assertEquals(ManeuverType.DESTINATION_RIGHT,state(25,0).type)
    }
}
