package com.openautolink.app.cluster

import android.content.res.Resources
import androidx.car.app.CarContext
import androidx.car.app.navigation.model.Maneuver
import com.openautolink.app.navigation.*
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class ManeuverSafetyTest {
    private val context = mockk<CarContext>(relaxed = true).also { c ->
        val r = mockk<Resources>(relaxed = true)
        every { r.getResourceName(any()) } returns "com.openautolink.app:drawable/maneuver"
        every { c.resources } returns r
        every { c.packageName } returns "com.openautolink.app"
    }
    private fun state(type: ManeuverType, exit: Int? = null, image: String? = null) =
        ManeuverState(type, 350, "350 m", "Road", null, navImageBase64 = image, roundaboutExitNumber = exit)

    @Test fun unknownAndUnspecifiedNeverInventDirection() {
        for (type in listOf(ManeuverType.UNKNOWN,
            ManeuverType.ROUNDABOUT_ENTER, ManeuverType.ROUNDABOUT_EXIT, ManeuverType.ROUNDABOUT_ENTER_AND_EXIT)) {
            val model = buildManeuver(state(type), context)
            assertEquals(type.name, Maneuver.TYPE_UNKNOWN, model.type)
            if (type == ManeuverType.UNKNOWN) assertNull(type.name, model.icon)
            else assertNotNull(type.name, model.icon) // known roundabout: neutral, no inferred circulation
            assertEquals(0, model.roundaboutExitNumber)
        }
    }
    @Test fun allTypesBuildWithoutImageOrWithInvalidImageAndNoInventedOrdinal() {
        for (type in ManeuverType.entries) for (image in listOf(null, "not a bitmap")) {
            val model = buildManeuver(state(type, image = image), context)
            assertEquals(type.name, 0, model.roundaboutExitNumber)
        }
    }
    @Test fun unspecifiedMergeUsesNeutralNamedPrimitive() {
        val model = buildManeuver(state(ManeuverType.MERGE_UNSPECIFIED), context)
        assertEquals(Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED,model.type)
        assertNotNull(model.icon)
    }
    @Test fun angleVocabularyUsesOnlyRepresentableOrdinalPrimitive() {
        for (rotation in listOf("cw","ccw")) {
            val type = ManeuverMapper.fromWire("roundabout_enter_and_exit_${rotation}_with_angle")
            val model = buildManeuver(state(type,3),context)
            assertEquals(3,model.roundaboutExitNumber)
            assertEquals(0,model.roundaboutExitAngle)
            assertEquals(Maneuver.TYPE_UNKNOWN,buildManeuver(state(type),context).type)
        }
    }
    @Test fun actualAndroidxConstructorConstraintsAreTypeSpecific() {
        val generic = listOf(Maneuver.TYPE_ROUNDABOUT_ENTER_CW,Maneuver.TYPE_ROUNDABOUT_ENTER_CCW,
            Maneuver.TYPE_ROUNDABOUT_EXIT_CW,Maneuver.TYPE_ROUNDABOUT_EXIT_CCW)
        for (type in generic) {
            assertEquals(0,Maneuver.Builder(type).build().roundaboutExitNumber)
            assertThrows(IllegalArgumentException::class.java) { Maneuver.Builder(type).setRoundaboutExitNumber(1) }
        }
        for (type in listOf(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW,Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW)) {
            assertThrows(IllegalArgumentException::class.java) { Maneuver.Builder(type).build() }
            assertEquals(2,Maneuver.Builder(type).setRoundaboutExitNumber(2).build().roundaboutExitNumber)
        }
        for (type in listOf(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE)) {
            assertThrows(IllegalArgumentException::class.java) { Maneuver.Builder(type).build() }
            assertEquals(90,Maneuver.Builder(type).setRoundaboutExitAngle(90).build().roundaboutExitAngle)
        }
    }
    @Test fun combinedRoundaboutsRequireRealPositiveOrdinal() {
        for (type in listOf(ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CW, ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CCW)) {
            for (exit in listOf(null, 0, -1)) assertEquals(Maneuver.TYPE_UNKNOWN, buildManeuver(state(type, exit), context).type)
            for (exit in 1..19) assertEquals(exit, buildManeuver(state(type, exit), context).roundaboutExitNumber)
        }
    }
}
