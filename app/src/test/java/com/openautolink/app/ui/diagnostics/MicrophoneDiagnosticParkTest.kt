package com.openautolink.app.ui.diagnostics

import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import org.junit.Assert.*
import org.junit.Test

class MicrophoneDiagnosticParkTest {
    private val observation = VehiclePropertyObservation(
        timestampElapsedNanos = 10_000_000_000L, receivedElapsedMs = 10_000, status = 0,
        registrationGeneration = 2, propertyId = 0x11400400, subscriptionActive = true,
    )
    private fun data(o: VehiclePropertyObservation = observation) = ControlMessage.VehicleData(
        gearRaw = 4, vhalRegistrationGeneration = 2, evObservationMetadata = mapOf("GEAR_SELECTION" to o),
    )
    @Test fun onlyFreshActualAvailableParkOrFreshOnChangeReadIsAccepted() {
        assertTrue(MicrophoneDiagnosticPark.isFreshPark(data(), true, 10_000))
        assertTrue(MicrophoneDiagnosticPark.isFreshPark(data(observation.copy(changeMode = 1,
            timestampElapsedNanos = 1_000_000L, statusReadElapsedMs = 10_000)), true, 20_000))
        for (bad in listOf(
            observation.copy(status = null), observation.copy(status = 1),
            observation.copy(source = "override"), observation.copy(propertyId = null),
            observation.copy(registrationGeneration = 1), observation.copy(subscriptionActive = false),
            observation.copy(timestampElapsedNanos = null), observation.copy(timestampElapsedNanos = -1),
            observation.copy(timestampElapsedNanos = 10_001_000_000L),
            observation.copy(receivedElapsedMs = 10_001), observation.copy(receivedElapsedMs = -1),
        )) assertFalse(bad.toString(), MicrophoneDiagnosticPark.isFreshPark(data(bad), true, 10_000))
        assertFalse(MicrophoneDiagnosticPark.isFreshPark(data(observation.copy(timestampElapsedNanos = 1_000_000L)), true, 20_000))
        assertFalse(MicrophoneDiagnosticPark.isFreshPark(data(observation.copy(timestampElapsedNanos = 1_000_000L,
            changeMode = 1, statusReadElapsedMs = 9_999)), true, 20_000))
        assertFalse(MicrophoneDiagnosticPark.isFreshPark(data(), true, 25_001))
        assertFalse(MicrophoneDiagnosticPark.isFreshPark(data().copy(gearRaw = 8), true, 10_000))
        assertFalse(MicrophoneDiagnosticPark.isFreshPark(data().copy(gearRaw = null, gear = "P"), true, 10_000))
        assertFalse(MicrophoneDiagnosticPark.isFreshPark(data(), false, 10_000))
        assertFalse(MicrophoneDiagnosticPark.isFreshPark(null, true, 10_000))
    }
}
