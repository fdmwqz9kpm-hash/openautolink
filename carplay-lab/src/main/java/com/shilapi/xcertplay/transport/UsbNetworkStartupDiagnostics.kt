// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.transport

/** Constant-space, numeric-only observations. Never retains packet or identity bytes. */
internal class UsbNetworkStartupDiagnostics(
    private val sink: (String) -> Unit,
    private val clockNs: () -> Long = System::nanoTime,
) {
    private val openedNs = clockNs()
    @Volatile private var attempts = 0
    @Volatile private var inbound = 0
    @Volatile private var outbound = 0
    private var filterDrops = 0
    private var macUnknownDrops = 0
    @Volatile private var detailsStopped = false
    private var retired = false
    @Synchronized fun stopDetails() { detailsStopped = true }
    @Synchronized fun retire() { retired = true; detailsStopped = true }
    enum class Direction { IN, OUT }
    enum class Decision(val wire: String) { FORWARD("forward"), FILTER_DROP("filter-drop"), MAC_UNKNOWN_DROP("mac-unknown-drop") }
    data class Send(val attempt: Int, val startNs: Long, val packetBytes: Int, val classification: String)

    private fun detailsEnabled(): Boolean {
        if (detailsStopped) return false
        if (clockNs()-openedNs >= 30_000_000_000L) {
            // Monotonic, one-way cutoff; no monitor or subsequent clock reads in steady state.
            detailsStopped = true
            return false
        }
        return true
    }

    fun packet(direction: Direction, packet: ByteArray, known: Boolean, decision: Decision) {
        // Volatile fast path only rejects. Startup admission is still revalidated under the monitor.
        if ((if (direction == Direction.IN) inbound else outbound) >= 16 || !detailsEnabled()) return
        val line = synchronized(this) {
            if (detailsStopped || clockNs()-openedNs >= 30_000_000_000L || (if (direction == Direction.IN) inbound else outbound) >= 16) return
            if (direction == Direction.IN) inbound++ else outbound++

            if (decision == Decision.FILTER_DROP) filterDrops++
            if (decision == Decision.MAC_UNKNOWN_DROP) macUnknownDrops++
            "USB network stage=packet direction=${direction.name.lowercase()} packetBytes=${packet.size} ${classify(packet)} " +
                "peerMacKnown=$known decision=${decision.wire} filterDrops=$filterDrops macUnknownDrops=$macUnknownDrops sinceOpenUs=${(clockNs()-openedNs).coerceAtLeast(0)/1000}"
        }
        runCatching { sink(line) }
    }

    fun beginSend(frame: ByteArray): Send? {
        if (attempts >= 16 || !detailsEnabled()) return null
        val send = synchronized(this) {
            if (detailsStopped || attempts >= 16 || clockNs()-openedNs >= 30_000_000_000L) return null
            attempts++
            val view = EthernetIpv6Codec.parseIpv6View(frame)
            val packet = view?.let { frame.copyOfRange(it.payloadOffset, it.payloadOffset + it.payloadLength) } ?: byteArrayOf()
            Send(attempts, clockNs(), packet.size, classify(packet))
        }
        runCatching { sink("USB network stage=send-start attempt=${send.attempt} packetBytes=${send.packetBytes} ${send.classification} startMonoNs=${send.startNs}") }
        return send
    }

    // Send.startNs marks the reservation. Production supplies the separate native IO interval.
    fun completeSend(send: Send?, transferred: Int, blockBytes: Int, timeoutMs: Int,
        writeStartNs: Long = send?.startNs ?: 0L, completedNs: Long = clockNs()) {
        if (send == null) return
        synchronized(this) { if (retired) return }
        val now = completedNs
        val result = if (transferred == blockBytes) "full" else if (transferred <= 0) "failed" else "short"
        runCatching { sink("USB network stage=send-end attempt=${send.attempt} packetBytes=${send.packetBytes} ${send.classification} " +
            "result=$result transferred=$transferred blockBytes=$blockBytes timeoutMs=$timeoutMs " +
            "startMonoNs=$writeStartNs elapsedUs=${(now-writeStartNs).coerceAtLeast(0)/1000} sinceOpenUs=${(now-openedNs).coerceAtLeast(0)/1000}") }
    }

    companion object {
        fun classify(packet: ByteArray): String {
            if (packet.size < 40 || (packet[0].toInt() ushr 4 and 15) != 6) return "next=unknown icmp=unknown flags=unknown"
            val length = ((packet[4].toInt() and 255) shl 8) or (packet[5].toInt() and 255)
            if (length == 0 || length + 40 > packet.size) return "next=unknown icmp=unknown flags=unknown"
            return when (packet[6].toInt() and 255) {
                6 -> if (length >= 20 && (packet[52].toInt() ushr 4 and 15) * 4 in 20..length)
                    "next=tcp icmp=unknown flags=${packet[53].toInt() and 0x17}" else "next=tcp icmp=unknown flags=unknown"
                58 -> "next=icmpv6 icmp=" + when {
                    length < 24 || packet[41].toInt() != 0 -> "unknown"
                    packet[40].toInt() and 255 == 135 -> "ns"
                    packet[40].toInt() and 255 == 136 -> "na"
                    else -> "unknown"
                } + " flags=unknown"
                17 -> "next=udp icmp=unknown flags=unknown"
                59 -> "next=none icmp=unknown flags=unknown"
                else -> "next=unknown icmp=unknown flags=unknown" // No blind extension-header traversal.
            }
        }
    }
}
