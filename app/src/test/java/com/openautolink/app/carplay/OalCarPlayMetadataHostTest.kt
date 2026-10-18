package com.openautolink.app.carplay

import android.content.Context
import com.openautolink.app.cluster.ClusterNavigationState
import com.openautolink.app.media.OalMediaSessionManager
import com.openautolink.carplaylab.metadata.CarPlayMediaMetadata
import com.openautolink.carplaylab.metadata.CarPlayNavigationMetadata
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class OalCarPlayMetadataHostTest {
    @Test fun hostForwardsOriginalMediaIngressDiagnosticToItsReceiptCallback() {
        val lines = mutableListOf<String>()
        val prefs = mockk<com.openautolink.app.data.AppPreferences>()
        mockkObject(com.openautolink.app.data.AppPreferences.Companion)
        every { com.openautolink.app.data.AppPreferences.getInstance(any()) } returns prefs
        every { prefs.clusterNavigation } returns kotlinx.coroutines.flow.flowOf(false)
        every { prefs.distanceUnits } returns kotlinx.coroutines.flow.flowOf("auto")
        val manager = mockk<OalMediaSessionManager>(relaxed = true)
        mockkObject(OalMediaSessionManager.Companion)
        every { OalMediaSessionManager.getInstance(any()) } returns manager
        val diagnostic = slot<(String) -> Unit>()
        every { manager.installCarPlayControls(any(), any(), capture(diagnostic)) } just Runs
        val constructor = OalCarPlayMetadataHost::class.java.declaredConstructors.find { it.parameterCount == 3 }
        // Exercise the existing host if the optional receipt constructor has not landed yet.
        val host = if (constructor == null) OalCarPlayMetadataHost(mockk<Context>()) else
            constructor.newInstance(mockk<Context>(), { line: String -> lines.add(line); Unit }, { _: Int -> false }) as OalCarPlayMetadataHost
        try {
            assertTrue(host.start())
            val line = "media stage=media-command-ingress command=STOP controller=unknown"
            diagnostic.captured(line)
            assertEquals("original STOP receipt must reach exact-link diagnostics", listOf(line), lines)
        } finally { host.close(); ClusterNavigationState.clear(); unmockkAll() }
    }

    @Test fun productionIntakeRetainsQuietSelectionAcrossDeltaGapWithoutReplacingRouteOrHostSuppression() {
        var now = 0L
        val intake = com.openautolink.carplaylab.metadata.CarPlayMetadataIntake { now }.apply { authenticate() }
        val host = OalCarPlayMetadataHost(mockk<Context>())
        val owner = requireNotNull(host.javaClass.getDeclaredField("owner").apply { isAccessible = true }.get(host))
        assertTrue(MetadataHostOwnership.process.claim(owner))
        fun publish() = host.publishRoute(intake.media, intake.routeMetadata, intake.artwork)
        fun select() = intake.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        try {
            intake.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2); string(4,"Turn road") })
            intake.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)); u32(10,350) })
            publish()
            assertEquals(350, ClusterNavigationState.state.value?.distanceMeters)
            val demand = ClusterNavigationState.recoveryDemand.value
            assertNotNull(demand)
            now = 30_110; intake.tick(); publish()
            assertNotNull("quiet selection must not flicker in real host publication", ClusterNavigationState.state.value)
            assertNull(ClusterNavigationState.state.value?.distanceMeters)
            assertTrue(ClusterNavigationState.isActive)
            assertEquals(demand, ClusterNavigationState.recoveryDemand.value)
            now = 56_000
            intake.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5201) { u8(1,1); u32(10,99) })
            publish()
            assertNotNull("real intake and host must retain turn on omitted-current delta", ClusterNavigationState.state.value)
            assertEquals(99, ClusterNavigationState.state.value?.distanceMeters)
            assertEquals(demand, ClusterNavigationState.recoveryDemand.value)
            assertEquals(99, intake.navigation?.distanceMeters)
            ClusterNavigationState.suppressCurrentRoute()
            now = 86_000; intake.tick(); publish()
            now = 112_000; select(); publish()
            assertNotNull(intake.navigation)
            assertTrue("same source epoch cannot override host stop", ClusterNavigationState.isRouteSuppressed)
            intake.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5201) { u8(1,0) })
            select(); publish()
            assertNull(ClusterNavigationState.state.value)
            assertFalse(ClusterNavigationState.isRouteSuppressed)
            assertNotEquals(demand, ClusterNavigationState.recoveryDemand.value)
        } finally { host.close(); ClusterNavigationState.clear(); unmockkAll() }
    }

    @Test fun loadingRouteHonorsHostSuppressionAndNewSourceEpochReplacesIt() {
        val host = OalCarPlayMetadataHost(mockk<Context>())
        val owner = requireNotNull(host.javaClass.getDeclaredField("owner").apply { isAccessible = true }.get(host))
        assertTrue(MetadataHostOwnership.process.claim(owner))
        try {
            val instruction = CarPlayNavigationMetadata(2,0,200,"Road",100,1000)
            host.publishRoute(null, com.openautolink.carplaylab.metadata.CarPlayRouteMetadata(1, instruction), null)
            val original = ClusterNavigationState.recoveryDemand.value
            assertNotNull(original)
            ClusterNavigationState.suppressCurrentRoute()
            host.publishRoute(null, com.openautolink.carplaylab.metadata.CarPlayRouteMetadata(1, null), null)
            assertTrue(ClusterNavigationState.isRouteSuppressed)
            assertNull(ClusterNavigationState.state.value)
            host.publishRoute(null, com.openautolink.carplaylab.metadata.CarPlayRouteMetadata(1, instruction), null)
            assertTrue("details cannot undo host stop", ClusterNavigationState.isRouteSuppressed)
            host.publishRoute(null, com.openautolink.carplaylab.metadata.CarPlayRouteMetadata(2, null), null)
            assertNotEquals(original, ClusterNavigationState.recoveryDemand.value)
            assertNotNull(ClusterNavigationState.recoveryDemand.value)
            assertTrue(ClusterNavigationState.isActive)
            assertFalse(ClusterNavigationState.isRouteSuppressed)
            host.publishRoute(null, null, null)
            assertFalse(ClusterNavigationState.isActive)
            assertNull(ClusterNavigationState.recoveryDemand.value)
        } finally { host.close(); ClusterNavigationState.clear(); unmockkAll() }
    }

    @Test fun publicationReachesExistingClusterAndProcessManagerAndStaleCloseDoesNotEraseAA() {
        val host = OalCarPlayMetadataHost(mockk<Context>())
        val manager = mockk<OalMediaSessionManager>(relaxed = true)
        val owner = requireNotNull(host.javaClass.getDeclaredField("owner").apply { isAccessible = true }.get(host))
        host.javaClass.getDeclaredField("mediaManager").apply { isAccessible = true }.set(host, manager)
        assertTrue(MetadataHostOwnership.process.claim(owner))
        try {
            val media = CarPlayMediaMetadata(title = "Song")
            host.publish(media, CarPlayNavigationMetadata(2,0,200,"Road",100,1000), null)
            assertEquals(200, ClusterNavigationState.state.value?.distanceMeters)
            assertNull(ClusterNavigationState.state.value?.etaSeconds)
            verify { manager.updateCarPlay(owner, media, null, true) }
            host.publish(media, null, null)
            assertNull(ClusterNavigationState.state.value)
            host.close()
            val aa = com.openautolink.app.navigation.ManeuverState(com.openautolink.app.navigation.ManeuverType.STRAIGHT, 100,"","AA",5)
            ClusterNavigationState.update(aa)
            host.close()
            assertEquals(aa, ClusterNavigationState.state.value)
        } finally { host.close(); ClusterNavigationState.clear(); unmockkAll() }
    }
}
