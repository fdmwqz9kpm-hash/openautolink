package com.openautolink.app.carplay

import com.openautolink.app.input.GnssForwarder
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import com.openautolink.carplaylab.intake.OalCarDataBootstrap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class CarPlayDrivingRestrictionIntakeTest {
    @Test fun actualWiredWrapperAndWirelessIntakeAuthenticateReplayAndRevokeExactRestrictionOwner() = runBlocking {
        for (wired in listOf(false,true)) {
            val source=MutableStateFlow(ControlMessage.VehicleData(gearRaw=4,vhalRegistrationGeneration=1,
                evObservationMetadata=mapOf("GEAR_SELECTION" to VehiclePropertyObservation(1_000_000_000,1000,0,
                    registrationGeneration=1,propertyId=0x11400400,subscriptionActive=true))))
            val clock={1000L};val sent=java.util.concurrent.CopyOnWriteArrayList<Boolean>()
            val receipts=java.util.concurrent.CopyOnWriteArrayList<String>()
            val link=OalCarPlayIntakeLink(false,OalCarPlayVehicleSnapshotAdapter({source},clock),
                OalCarPlayGnssSnapshotAdapter(clock) {object:GnssForwarder {
                    override val isActive=false
                    override fun start(){fail("unoffered GPS")}
                    override fun stop(){}
                }},clock,CarPlayAppearanceBridge(source,Dispatchers.IO,clock,5000),{false},{},receipts::add,
                restriction=CarPlayDrivingRestrictionBridge(source,Dispatchers.IO,clock,5000),
                sendLimitedUI={limited,guard -> sent.add(limited || !guard());true})
            val bootstrap=OalCarDataBootstrap(com.openautolink.carplaylab.intake.OalCarDataCapabilities(),clock,{})
            val runtime=if(wired)bootstrap.wrap(link) else link
            suspend fun count(n:Int) {withTimeout(2000){while(sent.size<n)delay(5)}}
            try {
                runtime.tick(true);delay(25);assertTrue(sent.isEmpty())
                runtime.authenticate();runtime.tick(false);delay(25);assertTrue(sent.isEmpty())
                runtime.tick(true);count(1);assertEquals(false,sent.last())
                source.value=source.value.copy(gearRaw=1);count(2);assertEquals(true,sent.last())
                source.value=source.value.copy(gearRaw=4);count(3);assertEquals(false,sent.last())
                runtime.tick(false);delay(25);runtime.tick(true);count(4);assertEquals(false,sent.last())
                runtime.revoke();runtime.close();val before=sent.size
                source.value=source.value.copy(gearRaw=8);delay(50);assertEquals(before,sent.size)
                assertTrue(receipts.any{it.contains("carplay-restriction reason=not_park limited=true result=transmitted")})
            } finally {runtime.close();bootstrap.close()}
        }
    }
}
