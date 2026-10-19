package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.ParcelFileDescriptor
import com.openautolink.carplaylab.usb.Ipv6NcmBridge
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.ByteArrayOutputStream

/** Uses the exact inbound forwarding method used by the production receive worker. */
class UsbNeighborDiscoveryReceiptTest {
    private val host = ByteArray(16).apply { this[0]=0xfe.toByte(); this[1]=0x80.toByte(); this[15]=7 }
    private fun ns(dad: Boolean = true): ByteArray = ByteArray(72).apply {
        this[0]=0x60; this[5]=32; this[6]=58; this[7]=255.toByte()
        if (!dad) { this[8]=0xfe.toByte(); this[9]=0x80.toByte(); this[23]=9 }
        this[24]=0xff.toByte(); this[25]=2; this[35]=1; this[36]=0xff.toByte(); this[39]=7
        this[40]=135.toByte(); host.copyInto(this,48)
        // Opaque nonce (DAD) or source-link-layer option (ordinary NS), never exported.
        this[64]=if(dad) 14 else 1; this[65]=1; this[66]=2
        checksum(this)
    }
    private fun checksum(packet: ByteArray) {
        packet[42]=0; packet[43]=0
        var sum=58L + packet.size-40
        for (i in 8 until 40 step 2) sum += ((packet[i].toInt() and 255) shl 8) or (packet[i+1].toInt() and 255)
        for (i in 40 until packet.size step 2) sum += ((packet[i].toInt() and 255) shl 8) or (packet.getOrElse(i+1) {0}.toInt() and 255)
        while (sum ushr 16 != 0L) sum=(sum and 65535)+(sum ushr 16)
        val check=sum.inv() and 65535; packet[42]=(check ushr 8).toByte();packet[43]=check.toByte()
    }
    private fun bridge(lines: MutableList<String>): Pair<Ipv6NcmBridge,NcmUsbBridge> {
        val ncm=NcmUsbBridge(mock(UsbDeviceConnection::class.java),mock(UsbEndpoint::class.java),mock(UsbEndpoint::class.java),null,emptyList(),null,lines::add)
        return Ipv6NcmBridge(ncm,mock(ParcelFileDescriptor::class.java),ByteArray(6)) {} to ncm
    }
    private fun frame(packet: ByteArray)=EthernetIpv6Codec.build(ByteArray(6),ByteArray(6),packet)
    private fun receipts(lines: List<String>)=lines.filter { it.startsWith("USB network stage=nd-receipt ") }

    @Test fun legacyFourArgumentConstructorRemainsAvailable() {
        assertNotNull("preserve existing non-trailing callback and JVM constructor callers",
            Ipv6NcmBridge::class.java.declaredConstructors.firstOrNull {it.parameterCount==4})
    }

    @Test fun oddAndMaximumLengthChecksumsIgnoreOnlyBytesBeyondDeclaredPayload() {
        val lines=mutableListOf<String>();val (bridge,ncm)=bridge(lines)
        try {
            for (length in listOf(25,65535)) {
                val packet=ns().copyOf(40+length).apply {
                    for(i in 64 until size) this[i]=0xff.toByte()
                    this[4]=(length ushr 8).toByte();this[5]=length.toByte();checksum(this)
                }
                val padded=packet.copyOf(packet.size+7).apply {this[lastIndex]=0x44}
                val output=ByteArrayOutputStream();bridge.forwardInbound(frame(padded),output)
                assertArrayEquals(padded,output.toByteArray())
                assertTrue(receipts(lines).last().contains("checksum=valid tunWrite=written"))
            }
        } finally {ncm.close()}
    }

    @Test fun configuredHostTargetIsComparedExactlyAndSnapshotDoesNotFollowCallerMutation() {
        val lines=mutableListOf<String>(); val (_,ncm)=bridge(lines)
        try {
            val constructor=Ipv6NcmBridge::class.java.declaredConstructors.firstOrNull {
                it.parameterCount==5 && it.parameterTypes[3]==ByteArray::class.java
            }
            assertNotNull("bridge must accept optional configured host IPv6 diagnostic context",constructor)
            val configured=host.copyOf()
            val bridge=constructor!!.newInstance(ncm,mock(ParcelFileDescriptor::class.java),ByteArray(6),configured,{} as () -> Unit) as Ipv6NcmBridge
            configured.fill(0) // exact configured address must be snapshotted, not alias caller storage
            val ordinary=ns(false)
            bridge.forwardInbound(frame(ordinary),ByteArrayOutputStream())
            assertEquals("USB network stage=nd-receipt receipt=1 icmp=ns shape=complete source=specified target=host hopLimit=valid checksum=valid tunWrite=written",receipts(lines).single())
            val dad=ns().apply {this[63]=8;this[39]=8;checksum(this)}
            bridge.forwardInbound(frame(dad),ByteArrayOutputStream())
            assertEquals("USB network stage=nd-receipt receipt=2 icmp=ns shape=complete source=unspecified target=other hopLimit=valid checksum=valid tunWrite=written",receipts(lines).last())
            bridge.forwardInbound(frame(ordinary.copyOf(63)),ByteArrayOutputStream())
            assertTrue(receipts(lines).last().contains("shape=truncated source=specified target=unknown"))
        } finally {ncm.close()}
    }

    @Test fun throwingTunWriteHasFailedReceiptAndPreservesException() {
        val lines=mutableListOf<String>(); val (bridge,ncm)=bridge(lines)
        val failure=java.io.IOException("synthetic private text must never be exported")
        try {
            try {
                bridge.forwardInbound(frame(ns(false)),object:java.io.OutputStream() {
                    override fun write(value: Int)=throw failure
                    override fun write(bytes: ByteArray) {
                        assertTrue(receipts(lines).isEmpty()); throw failure
                    }
                })
                fail("write exception must propagate unchanged")
            } catch (actual:java.io.IOException) { assertSame(failure,actual) }
            assertEquals(listOf("USB network stage=nd-receipt receipt=1 icmp=ns shape=complete source=specified target=unknown hopLimit=valid checksum=valid tunWrite=failed"),receipts(lines))
        } finally {ncm.close()}
    }

    @Test fun malformedNdIsObservedWithoutRejectingOrRepairingForwardedBytes() {
        val lines=mutableListOf<String>(); val (bridge,ncm)=bridge(lines)
        try {
            val cases=listOf(
                ns().apply {this[7]=64} to "shape=complete source=unspecified target=unknown hopLimit=invalid checksum=valid",
                ns().apply {this[66]=0x54} to "shape=complete source=unspecified target=unknown hopLimit=valid checksum=invalid",
                ns().copyOf(41) to "shape=truncated source=unspecified target=unknown hopLimit=valid checksum=unknown",
                ns().apply {this[41]=1;checksum(this)} to "shape=invalid source=unspecified target=unknown hopLimit=valid checksum=valid",
                ns().apply {this[5]=8;checksum(this)} to "shape=invalid source=unspecified target=unknown hopLimit=valid checksum=unknown",
                ns().apply {this[40]=136.toByte();checksum(this)} to "shape=complete source=unspecified target=unknown hopLimit=valid checksum=valid"
            )
            cases.forEachIndexed {i,(packet,fields) ->
                val output=ByteArrayOutputStream();bridge.forwardInbound(frame(packet),output)
                assertArrayEquals(packet,output.toByteArray())
                val kind=if(i==5) "na" else "ns"
                assertEquals("USB network stage=nd-receipt receipt=${i+1} icmp=$kind $fields tunWrite=written",receipts(lines).lastOrNull())
            }
            val before=receipts(lines).toList()
            listOf(ns().apply {this[6]=0},ns().apply {this[40]=128.toByte()},ns().copyOf(40)).forEach {bridge.forwardInbound(frame(it),ByteArrayOutputStream())}
            assertEquals("do not guess extension or absent ICMP headers",before,receipts(lines))
        } finally {ncm.close()}
    }

    @Test fun independentFirstEightAllowanceSurvivesPacketCapButHonorsStopAndRetirement() {
        for (boundary in listOf("cap","stop","retire")) {
            val lines=mutableListOf<String>(); val (bridge,ncm)=bridge(lines)
            try {
                repeat(16) {bridge.forwardInbound(frame(ns().apply {this[40]=128.toByte()}),ByteArrayOutputStream())}
                assertEquals(16,lines.count {it.startsWith("USB network stage=packet ")})
                repeat(9) {bridge.forwardInbound(frame(ns()),ByteArrayOutputStream())}
                assertEquals("ND is independently lifetime capped",8,receipts(lines).size)
                assertEquals(16,lines.count {it.startsWith("USB network stage=packet ")})
            } finally {ncm.close()}
            val later=mutableListOf<String>();val (other,owner)=bridge(later)
            try {
                val output=object:ByteArrayOutputStream() {
                    override fun write(bytes: ByteArray) {
                        if(boundary=="retire") owner.close() else owner.startupDiagnostics.stopDetails()
                        super.write(bytes)
                    }
                }
                other.forwardInbound(frame(ns()),output)
                other.forwardInbound(frame(ns()),ByteArrayOutputStream())
                assertTrue("no receipts after stop/retirement, including reserved write",receipts(later).isEmpty())
            } finally {owner.close()}
        }
    }

    @Test fun validDadReceiptAppearsOnlyAfterSuccessfulTunWriteWithoutChangingBytes() {
        val lines=mutableListOf<String>(); val (bridge,ncm)=bridge(lines)
        try {
            val packet=ns(); val output=object:ByteArrayOutputStream() {
                override fun write(bytes: ByteArray) {
                    assertTrue("no TUN success before actual write",receipts(lines).isEmpty())
                    super.write(bytes)
                }
            }
            bridge.forwardInbound(frame(packet),output)
            assertArrayEquals(packet,output.toByteArray())
            assertEquals(listOf("USB network stage=nd-receipt receipt=1 icmp=ns shape=complete source=unspecified target=unknown hopLimit=valid checksum=valid tunWrite=written"),receipts(lines))
        } finally { ncm.close() }
    }
}
