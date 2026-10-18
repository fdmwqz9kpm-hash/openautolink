package com.openautolink.app.carplay

import com.openautolink.app.input.GnssForwarder
import com.openautolink.app.transport.ControlMessage
import org.junit.Assert.*
import org.junit.Test

class CarPlayGnssSourceTimeTest {
    private val gga="\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
    @Test fun repeatedOldMeasurementCannotRefreshReceiptOnlyCacheAndMissingFutureOrClockJumpFailsClosed() {
        var elapsed=1000L;var unix=1_800_000_000_000L
        lateinit var sink:(ControlMessage.Gnss)->Unit
        val adapter=OalCarPlayGnssSnapshotAdapter({elapsed},requireSourceTime=true,unixMs={unix}) {
            sink=it
            object:GnssForwarder {override var isActive=false;override fun start(){isActive=true};override fun stop(){isActive=false}}
        }
        try {
            adapter.setActive(true)
            val fix=unix
            sink(ControlMessage.Gnss(gga,fix,unix,elapsed));assertEquals(gga+"\r\n",adapter.latest(setOf(1)))
            elapsed+=2500;unix+=2500
            sink(ControlMessage.Gnss(gga,fix,unix,elapsed));assertNotNull(adapter.latest(setOf(1)))
            elapsed+=501;unix+=501
            assertNull("source age accumulates rather than resetting at repeated callback",adapter.latest(setOf(1)))
            sink(ControlMessage.Gnss(gga,fix,unix,elapsed));assertNull(adapter.latest(setOf(1)))
            sink(ControlMessage.Gnss(gga));assertNull("unknown real source age is not zero",adapter.latest(setOf(1)))
            sink(ControlMessage.Gnss(gga,unix+1,unix,elapsed));assertNull("future GNSS time",adapter.latest(setOf(1)))
            sink(ControlMessage.Gnss(gga,unix,unix,elapsed));assertNotNull(adapter.latest(setOf(1)))
            unix+=60_000;assertNull("wall-clock discontinuity invalidates correlation",adapter.latest(setOf(1)))
            sink(ControlMessage.Gnss(gga,unix,unix,elapsed));assertNotNull(adapter.latest(setOf(1)))
            adapter.setActive(false);assertNull(adapter.latest(setOf(1)))
        } finally {adapter.close()}
    }
    @Test fun queuedCallbackAgeAndElapsedRollbackAreNotRestampedFresh() {
        var elapsed=5000L;var unix=1_800_000_000_000L
        lateinit var sink:(ControlMessage.Gnss)->Unit
        val adapter=OalCarPlayGnssSnapshotAdapter({elapsed},true,{unix}) {
            sink=it;object:GnssForwarder {override var isActive=false;override fun start(){isActive=true};override fun stop(){isActive=false}}
        }
        try {
            adapter.setActive(true)
            sink(ControlMessage.Gnss(gga,unix-3001,unix-3001,elapsed-3001))
            assertNull(adapter.latest(setOf(1)))
            sink(ControlMessage.Gnss(gga,unix,unix,elapsed+1));assertNull(adapter.latest(setOf(1)))
            sink(ControlMessage.Gnss(gga,unix-2000,unix,elapsed));assertNotNull(adapter.latest(setOf(1)))
            elapsed+=1001;unix+=1001;assertNull(adapter.latest(setOf(1)))
        } finally {adapter.close()}
    }
    @Test fun qualifiedSourceReachesActualRequestedFffbWithOriginalUnitsAndStopsWhenFixTimeExpires() {
        var elapsed=1000L;var unix=1_800_000_000_000L
        lateinit var sink:(ControlMessage.Gnss)->Unit
        val gps=OalCarPlayGnssSnapshotAdapter({elapsed},true,{unix}) {
            sink=it;object:GnssForwarder {override var isActive=false;override fun start(){isActive=true};override fun stop(){isActive=false}}
        }
        val vehicle=kotlinx.coroutines.flow.MutableStateFlow(ControlMessage.VehicleData())
        val sent=mutableListOf<com.shilapi.xcertplay.iap2.wire.Iap2Frame>()
        val link=OalCarPlayIntakeLink(true,OalCarPlayVehicleSnapshotAdapter({vehicle},{elapsed}),gps,{elapsed},
            CarPlayAppearanceBridge(vehicle,kotlinx.coroutines.Dispatchers.IO,{elapsed},5000),{false},{sent+=it},{})
        val body="GNRMC,123519,A,4807.038,N,01131.000,E,22.4,84.4,230394,,"
        val rmc="\$"+body+"*"+"%02X".format(body.fold(0){a,c->a xor c.code})
        try {
            link.authenticate()
            link.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0xFFFA) {u32(0,250);void(1);void(2)})
            val fix=unix
            sink(ControlMessage.Gnss(gga,fix,unix,elapsed));sink(ControlMessage.Gnss(rmc,fix,unix,elapsed))
            elapsed+=250;unix+=250 // Start already attempted at t=1000; respect the requested interval.
            link.tick(true)
            assertEquals(listOf(0xFFFB),sent.map {it.messageId})
            val wire=com.shilapi.xcertplay.iap2.body.Iap2BodyReader.of(sent.single()).string(0)
            assertEquals(gga+"\r\n"+rmc+"\r\n",wire) // metres, knots, UTC/date untouched, not VHAL speed.
            elapsed+=3001;unix+=3001
            sink(ControlMessage.Gnss(gga,fix,unix,elapsed));sink(ControlMessage.Gnss(rmc,fix,unix,elapsed))
            link.tick(true);assertEquals(1,sent.size)
            link.handle(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0xFFFC,byteArrayOf()))
            sink(ControlMessage.Gnss(gga,unix,unix,elapsed));link.tick(true);assertEquals(1,sent.size)
        } finally {link.close()}
    }
}
