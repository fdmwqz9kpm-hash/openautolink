package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class UsbNetworkStartupDiagnosticsTest {
    private fun packet(next: Int, length: Int = 24) = ByteArray(40+length).apply {
        this[0]=0x60; this[4]=(length ushr 8).toByte(); this[5]=length.toByte(); this[6]=next.toByte()
    }
    private fun frame() = EthernetIpv6Codec.build(ByteArray(6),ByteArray(6),packet(58).apply { this[40]=135.toByte() })

    @Test fun disabledCallsNeverWaitForCrossThreadDiagnosticMonitor() {
        for (reason in listOf("stop", "retire", "deadline", "in-cap", "out-cap", "send-cap", "nd-cap")) {
            val now=java.util.concurrent.atomic.AtomicLong(1_000_000L)
            val lines=java.util.concurrent.CopyOnWriteArrayList<String>()
            val trace=UsbNetworkStartupDiagnostics(lines::add) {now.get()}
            fun packet(direction: UsbNetworkStartupDiagnostics.Direction) = trace.packet(
                direction, byteArrayOf(), false, UsbNetworkStartupDiagnostics.Decision.FILTER_DROP)
            when (reason) {
                "stop" -> trace.stopDetails()
                "retire" -> trace.retire()
                "deadline" -> now.addAndGet(30_000_000_000L)
                "in-cap" -> repeat(16) { packet(UsbNetworkStartupDiagnostics.Direction.IN) }
                "out-cap" -> repeat(16) { packet(UsbNetworkStartupDiagnostics.Direction.OUT) }
                "send-cap" -> repeat(16) { trace.beginSend(frame()) }
                "nd-cap" -> repeat(8) { trace.beginNeighbor(this.packet(58).apply {this[40]=135.toByte()}) }
            }
            val before=lines.toList()
            val locked=java.util.concurrent.CountDownLatch(1)
            val release=java.util.concurrent.CountDownLatch(1)
            val workers=java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val holder=workers.submit {
                    synchronized(trace) { locked.countDown(); check(release.await(5,java.util.concurrent.TimeUnit.SECONDS)) }
                }
                assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS))
                val calls=workers.submit {
                    repeat(32) {
                        if (reason !in listOf("out-cap", "send-cap", "nd-cap")) packet(UsbNetworkStartupDiagnostics.Direction.IN)
                        if (reason !in listOf("in-cap", "send-cap", "nd-cap")) packet(UsbNetworkStartupDiagnostics.Direction.OUT)
                        if (reason !in listOf("in-cap", "out-cap", "nd-cap")) assertNull(trace.beginSend(frame()))
                        if (reason in listOf("stop", "retire", "deadline", "nd-cap"))
                            assertNull(trace.beginNeighbor(this.packet(58).apply {this[40]=135.toByte()}))
                    }
                }
                try { calls.get(1,java.util.concurrent.TimeUnit.SECONDS) }
                catch (_: java.util.concurrent.TimeoutException) { fail("disabled calls ($reason) waited for diagnostic monitor") }
                assertEquals("disabled calls ($reason) cannot deliver observers",before,lines)
                release.countDown(); holder.get(5,java.util.concurrent.TimeUnit.SECONDS)
            } finally { release.countDown(); workers.shutdownNow() }
        }
    }

    @Test fun detailCountAndMonotonicStartupDeadlineAreIndependentCaps() {
        val lines=mutableListOf<String>(); var now=1_000_000L
        val trace=UsbNetworkStartupDiagnostics(lines::add) { now }
        repeat(100) { trace.completeSend(trace.beginSend(frame()),-1,106,20) }
        assertEquals("constant first sixteen completions",16,lines.count { it.startsWith("USB network stage=send-end") })
        val later=mutableListOf<String>()
        val timed=UsbNetworkStartupDiagnostics(later::add) { now }
        now += 30_000_000_001L
        timed.completeSend(timed.beginSend(frame()),-1,106,20)
        assertTrue("no detail after startup deadline",later.isEmpty())
    }

    @Test fun malformedTruncatedAndExtensionHeadersNeverGuessTransportFields() {
        val unknown="next=unknown icmp=unknown flags=unknown"
        for (p in listOf(ByteArray(0),ByteArray(39),packet(6).copyOf(45),packet(6).apply { this[0]=0x40 },packet(0),packet(43),packet(44),packet(60),packet(51),packet(50)))
            assertEquals(unknown,UsbNetworkStartupDiagnostics.classify(p))
        assertEquals("next=tcp icmp=unknown flags=unknown",UsbNetworkStartupDiagnostics.classify(packet(6,19)))
        assertEquals("next=tcp icmp=unknown flags=unknown",UsbNetworkStartupDiagnostics.classify(packet(6).apply {this[52]=0x40;this[53]=0x12}))
        assertEquals("next=icmpv6 icmp=unknown flags=unknown",UsbNetworkStartupDiagnostics.classify(packet(58,1)))
        assertEquals("next=icmpv6 icmp=ns flags=unknown",UsbNetworkStartupDiagnostics.classify(packet(58).apply {this[40]=135.toByte()}))
        assertEquals("next=icmpv6 icmp=na flags=unknown",UsbNetworkStartupDiagnostics.classify(packet(58).apply {this[40]=136.toByte()}))
        assertEquals("next=icmpv6 icmp=unknown flags=unknown",UsbNetworkStartupDiagnostics.classify(packet(58).apply {this[40]=136.toByte();this[41]=1}))
        assertEquals("next=tcp icmp=unknown flags=18",UsbNetworkStartupDiagnostics.classify(packet(6).apply {this[52]=0x50;this[53]=0x12}))
        repeat(512) { length -> UsbNetworkStartupDiagnostics.classify(ByteArray(length) { (it*71).toByte() }) }
    }

    @Test fun packetCapsStopAndRetirementKeepOwnersIndependentAndReserveLateCompletion() {
        var now=1_000_000L;val lines=mutableListOf<String>()
        val trace=UsbNetworkStartupDiagnostics(lines::add) {now}
        repeat(100) {
            trace.packet(UsbNetworkStartupDiagnostics.Direction.IN,byteArrayOf(),false,UsbNetworkStartupDiagnostics.Decision.FILTER_DROP)
            trace.packet(UsbNetworkStartupDiagnostics.Direction.OUT,byteArrayOf(),false,UsbNetworkStartupDiagnostics.Decision.MAC_UNKNOWN_DROP)
        }
        assertEquals(32,lines.size)
        assertTrue(lines.last().contains("filterDrops=16 macUnknownDrops=16"))
        val pending=trace.beginSend(frame())
        trace.stopDetails();now+=31_000_000_000L
        assertNull(trace.beginSend(frame()))
        trace.completeSend(pending,106,106,20)
        assertTrue(lines.last().contains("result=full"))
        val frozen=lines.toList();trace.retire()
        trace.completeSend(pending,1,106,20)
        trace.packet(UsbNetworkStartupDiagnostics.Direction.IN,byteArrayOf(),true,UsbNetworkStartupDiagnostics.Decision.FORWARD)
        assertEquals(frozen,lines)
        val replacement=UsbNetworkStartupDiagnostics(lines::add) {now}
        assertNotNull(replacement.beginSend(frame()))
    }
}
