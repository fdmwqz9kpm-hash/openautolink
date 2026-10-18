package com.openautolink.app.cluster

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source ownership checks supplement (not replace) real host-model tests. */
class OalClusterSafetyWiringTest {
    @Test fun nonGmBuildsBeforeStartingAndWithdrawsFailedOrMissingGuidance() {
        val source = File("src/main/java/com/openautolink/app/cluster/OalClusterSession.kt").readText()
        val process = source.substringAfter("private fun processStateUpdate").substringBefore("private fun buildTrip")
        assertTrue(process.indexOf("buildTrip(maneuver)") < process.indexOf("navManager.navigationStarted()"))
        assertTrue(process.contains("withdrawGuidance(navManager)"))
        assertTrue(source.contains("ClusterNavigationState.isRouteSuppressed"))
        val callback = source.substringAfter("override fun onStopNavigation()").substringBefore("override fun onAutoDriveEnabled")
        assertFalse(callback.contains("navigationStarted()"))
        assertTrue(callback.contains("suppressCurrentRoute()"))
    }
}
