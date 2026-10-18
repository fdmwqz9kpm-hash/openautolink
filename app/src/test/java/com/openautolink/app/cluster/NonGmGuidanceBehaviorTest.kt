package com.openautolink.app.cluster

import android.app.Application
import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.ManeuverType
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class, sdk=[32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NonGmGuidanceBehaviorTest {
    private val dispatcher = StandardTestDispatcher()
    private val sessions = mutableListOf<Fixture>()
    private val instruction = ManeuverState(ManeuverType.TURN_LEFT,350,"350 m","Road",120)
    private class Fixture(val session: OalClusterSession, val manager: NavigationManager,
        val callback: NavigationManagerCallback, val screen: OalClusterScreen, val lifecycle: LifecycleRegistry)

    @Before fun setup() { Dispatchers.setMain(dispatcher); ClusterNavigationState.clearHost() }
    @After fun cleanup() {
        sessions.forEach { it.lifecycle.currentState = Lifecycle.State.DESTROYED }
        ClusterNavigationState.clearHost(); Dispatchers.resetMain(); unmockkAll()
    }
    private fun fixture(): Fixture {
        val context=mockk<CarContext>(relaxed=true)
        val app=RuntimeEnvironment.getApplication()
        every { context.resources } returns app.resources
        every { context.packageName } returns app.packageName
        val manager=mockk<NavigationManager>(relaxed=true)
        every { context.getCarService(NavigationManager::class.java) } returns manager
        val callback=slot<NavigationManagerCallback>()
        every { manager.setNavigationManagerCallback(capture(callback)) } just Runs
        val session=spyk(OalClusterSession())
        every { session.carContext } returns context
        val lifecycle=LifecycleRegistry(session)
        every { session.lifecycle } returns lifecycle
        lifecycle.currentState=Lifecycle.State.CREATED
        val screen=session.onCreateScreen(Intent()) as OalClusterScreen
        return Fixture(session,manager,callback.captured,screen,lifecycle).also { sessions.add(it) }
    }
    private fun settle() { dispatcher.scheduler.runCurrent(); dispatcher.scheduler.advanceTimeBy(250); dispatcher.scheduler.runCurrent() }
    private fun assertBlank(f: Fixture) { assertNull((f.screen.onGetTemplate() as NavigationTemplate).navigationInfo) }

    @Test fun stopDuringStartCannotPublishTripOrRestoreNavigatingFlag() {
        val f=fixture()
        every { f.manager.navigationStarted() } answers { f.callback.onStopNavigation() }
        ClusterNavigationState.updateHost(instruction); settle()
        assertTrue(ClusterNavigationState.isRouteSuppressed)
        verify(exactly=0) { f.manager.updateTrip(any()) }
        assertBlank(f)
        val flag=OalClusterSession::class.java.getDeclaredField("isNavigating").apply { isAccessible=true }
        assertEquals(false,flag.get(f.session))
    }
    @Test fun stopDuringUpdateCannotRepublishStoppedScreen() {
        val f=fixture()
        every { f.manager.updateTrip(any()) } answers { f.callback.onStopNavigation() }
        ClusterNavigationState.updateHost(instruction); settle()
        assertTrue(ClusterNavigationState.isRouteSuppressed)
        assertBlank(f)
    }
    @Test fun lateOldRouteStopDoesNotSuppressReplacementRoute() {
        val f=fixture()
        ClusterNavigationState.updateHost(instruction); settle()
        ClusterNavigationState.clearHost(); ClusterNavigationState.updateHost(instruction.copy(roadName="New road"))
        val replacement=ClusterNavigationState.recoveryDemand.value
        f.callback.onStopNavigation()
        assertEquals(replacement,ClusterNavigationState.recoveryDemand.value)
        assertFalse(ClusterNavigationState.isRouteSuppressed)
        settle()
        assertNotNull((f.screen.onGetTemplate() as NavigationTemplate).navigationInfo)
    }
    @Test fun retiredSessionCallbackCannotSuppressReplacementSession() {
        val old=fixture()
        ClusterNavigationState.updateHost(instruction); settle()
        old.lifecycle.currentState=Lifecycle.State.DESTROYED
        val replacement=fixture(); settle()
        old.callback.onStopNavigation()
        assertFalse(ClusterNavigationState.isRouteSuppressed)
        assertNotNull((replacement.screen.onGetTemplate() as NavigationTemplate).navigationInfo)
    }
    @Test fun updateFailureRearmsOnCoalescedClearAndIdenticalNewEpoch() {
        val f=fixture()
        every { f.manager.updateTrip(any()) } throws IllegalStateException("host rejects")
        ClusterNavigationState.updateHost(instruction); settle()
        verify(exactly=1) { f.manager.updateTrip(any()) }
        every { f.manager.updateTrip(any()) } just Runs
        ClusterNavigationState.clearHost(); ClusterNavigationState.updateHost(instruction); settle()
        verify(exactly=2) { f.manager.updateTrip(any()) }
        assertNotNull((f.screen.onGetTemplate() as NavigationTemplate).navigationInfo)
    }
    @Test fun buildFailureRearmsOnCoalescedClearAndIdenticalNewEpoch() {
        val f=fixture()
        every { f.session.carContext } throws IllegalStateException("temporarily unavailable context")
        ClusterNavigationState.updateHost(instruction); settle()
        verify(exactly=0) { f.manager.navigationStarted() }
        val context=mockk<CarContext>(relaxed=true)
        val app=RuntimeEnvironment.getApplication()
        every { context.resources } returns app.resources
        every { context.packageName } returns app.packageName
        every { f.session.carContext } returns context
        ClusterNavigationState.clearHost(); ClusterNavigationState.updateHost(instruction); settle()
        verify(exactly=1) { f.manager.updateTrip(any()) }
    }
    @Test fun realParserExpiredDistanceRemainsUnknownInTripAndRoutingInfoIsOmitted() {
        var now=0L
        val intake=com.openautolink.carplaylab.metadata.CarPlayMetadataIntake { now }.apply { authenticate() }
        val host=com.openautolink.app.carplay.OalCarPlayMetadataHost(mockk<android.content.Context>())
        val owner=host.javaClass.getDeclaredField("owner").apply { isAccessible=true }.get(host)!!
        assertTrue(com.openautolink.app.carplay.MetadataHostOwnership.process.claim(owner))
        try {
            val f=fixture()
            val trips=mutableListOf<Trip>()
            every { f.manager.updateTrip(capture(trips)) } just Runs
            intake.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2); string(4,"Turn road") })
            intake.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)); u32(10,350) })
            host.publishRoute(intake.media,intake.routeMetadata,intake.artwork); settle()
            assertNotNull(trips.last().stepTravelEstimates.single().remainingDistance)
            now=30_110; intake.tick()
            host.publishRoute(intake.media,intake.routeMetadata,intake.artwork); settle()
            val trip=trips.last()
            assertEquals("Turn road",trip.steps.single().cue!!.toString())
            val estimate=trip.stepTravelEstimates.single()
            assertNull(estimate.remainingDistance)
            assertNull(estimate.arrivalTimeAtDestination)
            assertEquals(TravelEstimate.REMAINING_TIME_UNKNOWN,estimate.remainingTimeSeconds)
            assertBlank(f)
        } finally { host.close() }
    }
}
