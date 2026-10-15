package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.ParcelFileDescriptor
import com.openautolink.carplaylab.usb.Ipv6NcmBridge
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.io.ByteArrayOutputStream

/** Exercises the exact forwarding functions called by both production IO loops and real NCM send. */
class UsbNetworkForwardingDiagnosticsTest {
    private fun packet(multicast:Boolean=false)=ByteArray(64).apply {
        this[0]=0x60;this[5]=24;this[6]=58;this[40]=135.toByte()
        this[8]=0xfe.toByte();this[9]=0x80.toByte()
        this[24]=if(multicast) 0xff.toByte() else 0xfe.toByte();this[25]=if(multicast) 2 else 0x80.toByte()
    }
    @Test fun exactForwardersObserveFilterUnknownMacLearningAndActualUsbResultWithoutChangingBytes() {
        val lines=mutableListOf<String>();val blocks=mutableListOf<ByteArray>()
        val connection=mock(UsbDeviceConnection::class.java)
        `when`(connection.bulkTransfer(any(UsbEndpoint::class.java),any(ByteArray::class.java),anyInt(),anyInt())).thenAnswer {
            blocks+=it.getArgument<ByteArray>(1).copyOf(); -1
        }
        val ncm=NcmUsbBridge(connection,mock(UsbEndpoint::class.java),mock(UsbEndpoint::class.java),null,emptyList(),null,lines::add)
        val host=byteArrayOf(2,1,2,3,4,5);val peer=byteArrayOf(2,5,4,3,2,1)
        val bridge=Ipv6NcmBridge(ncm,mock(ParcelFileDescriptor::class.java),host) { fail("diagnostics must not fail IO") }
        val output=ByteArrayOutputStream()
        bridge.forwardOutbound(ByteArray(12))
        bridge.forwardOutbound(packet()) // unknown unicast peer: unchanged drop
        assertTrue(blocks.isEmpty())
        bridge.forwardOutbound(packet(true)) // multicast does not need learned peer
        bridge.forwardInbound(EthernetIpv6Codec.build(peer,host,packet()),output)
        bridge.forwardOutbound(packet()) // now real USB attempt
        assertArrayEquals(packet(),output.toByteArray())
        assertEquals(2,blocks.size)
        assertArrayEquals(EthernetIpv6Codec.build(host,peer,packet()),Ntb16Codec.parse(blocks.last()).single())
        val packets=lines.filter {it.startsWith("USB network stage=packet")}
        assertEquals("all inbound and outbound decisions",5,packets.size)
        assertTrue(packets[0].contains("decision=filter-drop"))
        assertTrue(packets[1].contains("decision=mac-unknown-drop"))
        assertTrue(packets[2].contains("peerMacKnown=false"))
        assertTrue(packets[3].contains("direction=in") && packets[3].contains("peerMacKnown=true"))
        assertTrue(packets[4].contains("peerMacKnown=true"))
        assertTrue(packets.last().contains("filterDrops=1 macUnknownDrops=1"))
        assertEquals(2,lines.count {it.startsWith("USB network stage=send-start")})
        assertEquals(2,lines.count {it.startsWith("USB network stage=send-end") && it.contains("result=failed")})
        ncm.close()
        val frozen=lines.toList()
        bridge.forwardInbound(EthernetIpv6Codec.build(peer,host,packet()),output)
        assertEquals("retired owner cannot publish packet traces",frozen,lines)
    }
}
