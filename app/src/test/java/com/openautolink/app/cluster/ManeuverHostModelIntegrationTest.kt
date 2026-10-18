package com.openautolink.app.cluster

import android.app.Application
import android.graphics.Bitmap
import android.util.Base64
import androidx.car.app.CarContext
import androidx.car.app.Session
import androidx.car.app.navigation.model.*
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.ManeuverType
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream

/** Real AndroidX builders/consumers; no fake Maneuver, Step, RoutingInfo or Trip. */
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class, sdk=[32])
@ConscryptMode(ConscryptMode.Mode.OFF)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ManeuverHostModelIntegrationTest {
    private val context = mockk<CarContext>(relaxed=true).also { c ->
        val app = RuntimeEnvironment.getApplication()
        every { c.resources } returns app.resources
        every { c.packageName } returns app.packageName
    }
    private fun state(type: ManeuverType, image: String?, exit: Int?=3) = ManeuverState(type,350,"350 m","Road",120,
        navImageBase64=image, roundaboutExitNumber=exit, destination="Destination",timeToArrivalSeconds=120,
        destDistanceMeters=1500)
    private fun validPng(): String {
        val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        val out=ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG,100,out)
        return Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)
    }
    @Test fun allTypesAndBitmapStatesBuildRealTripsOnBothConsumers() {
        val sessions = listOf<Session>(spyk(OalClusterSession()),spyk(ClusterMainSession()))
        for (session in sessions) {
            every { session.carContext } returns context
            val build=session.javaClass.getDeclaredMethod("buildTrip",ManeuverState::class.java).apply { isAccessible=true }
            for (type in ManeuverType.entries) for (image in listOf(null,"invalid bitmap",validPng())) {
                val mapped=state(type,image)
                val trip=build.invoke(session,mapped) as Trip
                val step=trip.steps.single()
                assertEquals("Road",step.road!!.toString())
                val model=requireNotNull(step.maneuver)
                assertEquals(buildManeuver(mapped,context).type,model.type)
                assertEquals(1,trip.destinations.size)
                assertFalse(trip.isLoading)
            }
        }
    }
    @Test fun bitmapCannotHideMissingOrdinalOrOverrideCorrectNamedEnum() {
        val png=validPng()
        for (type in ManeuverType.entries) {
            val absent=buildManeuver(state(type,null,exit=null),context)
            val valid=buildManeuver(state(type,png,exit=null),context)
            assertEquals(type.name,absent.type,valid.type)
            assertEquals(type.name,absent.roundaboutExitNumber,valid.roundaboutExitNumber)
            assertEquals(type.name,androidx.core.graphics.drawable.IconCompat.TYPE_BITMAP,valid.icon!!.icon!!.type)
        }
        assertEquals(Maneuver.TYPE_TURN_NORMAL_LEFT,buildManeuver(state(ManeuverType.TURN_LEFT,png),context).type)
    }
    @Test fun nonGmUnavailableGuidanceEndsHostWithoutEndingRouteThenRestartsOnReady() {
        val session=spyk(OalClusterSession())
        every { session.carContext } returns context
        val manager=mockk<androidx.car.app.navigation.NavigationManager>(relaxed=true)
        OalClusterSession::class.java.getDeclaredField("navigationManager").apply { isAccessible=true }.set(session,manager)
        val process=OalClusterSession::class.java.getDeclaredMethod("processStateUpdate",ManeuverState::class.java)
            .apply { isAccessible=true }
        try {
            ClusterNavigationState.clearHost()
            val mapped=state(ManeuverType.UNKNOWN,null)
            ClusterNavigationState.updateHost(mapped)
            process.invoke(session,mapped)
            verify(exactly=1) { manager.navigationStarted() }
            verify(exactly=1) { manager.updateTrip(any()) }
            ClusterNavigationState.updateHost(null)
            process.invoke(session,null)
            assertTrue(ClusterNavigationState.isActive)
            verify(exactly=1) { manager.navigationEnded() }
            ClusterNavigationState.updateHost(mapped)
            process.invoke(session,mapped)
            verify(exactly=2) { manager.navigationStarted() }
            verify(exactly=2) { manager.updateTrip(any()) }
        } finally { ClusterNavigationState.clearHost() }
    }
    @Test fun nonGmTemplateKeepsRoadAndDistanceForUnsupportedDirection() {
        val screen=OalClusterScreen(context)
        for (type in ManeuverType.entries) {
            screen.updateState(state(type,null).copy(lanes=listOf(
                com.openautolink.app.navigation.LaneInfo(listOf(
                    com.openautolink.app.navigation.LaneDirectionInfo("straight",true))))))
            val template=screen.onGetTemplate() as NavigationTemplate
            val routing=template.navigationInfo as RoutingInfo
            assertEquals("Road",routing.currentStep!!.road!!.toString())
            assertNotNull(routing.currentDistance)
        }
    }
}
