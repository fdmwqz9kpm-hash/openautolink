package com.openautolink.app.ui.diagnostics

import com.openautolink.app.transport.ControlMessage

object MicrophoneDiagnosticPark {
    /** Read-only process VHAL evidence. Never consults projection or Always-in-Park preferences. */
    fun isFreshPark(
        data: ControlMessage.VehicleData? = com.openautolink.app.input.ProcessVehicleDataRuntime.latestVehicleData()?.value,
        active: Boolean = com.openautolink.app.input.ProcessVehicleDataRuntime.isActive(),
        now: Long = android.os.SystemClock.elapsedRealtime(),
    ): Boolean {
        if (!active || data == null || data.gearRaw != 4 || now < 0) return false
        val o = data.evObservationMetadata["GEAR_SELECTION"] ?: return false
        if (o.source != "vhal" || o.propertyId != 0x11400400 || !o.subscriptionActive || o.status != 0) return false
        val generation = data.vhalRegistrationGeneration ?: return false
        if (generation <= 0 || o.registrationGeneration != generation) return false
        val timestamp = o.timestampElapsedNanos ?: return false
        if (timestamp <= 0 || timestamp / 1_000_000 > now || o.receivedElapsedMs < 0 || o.receivedElapsedMs > now) return false
        val maxAgeMs = 15_000L
        val read = o.statusReadElapsedMs
        // An actual fresh ONCHANGE getProperty can confirm an old unchanged event.
        val liveRead = o.changeMode == 1 && read != null && read == o.receivedElapsedMs &&
            read <= now && now - read <= maxAgeMs
        return now - o.receivedElapsedMs <= maxAgeMs &&
            (liveRead || now - timestamp / 1_000_000 <= maxAgeMs)
    }
}
