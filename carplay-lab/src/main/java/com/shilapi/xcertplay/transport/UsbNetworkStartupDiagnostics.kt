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

    @Volatile private var neighborReceipts = 0
    data class Neighbor(val receipt: Int, val fields: String)

    /** Separate first-eight allowance: unrelated packets cannot bury neighbor-discovery evidence. */
    fun beginNeighbor(packet: ByteArray, hostIpv6: ByteArray? = null): Neighbor? {
        if (neighborReceipts >= 8 || !detailsEnabled()) return null
        return synchronized(this) {
            if (neighborReceipts >= 8 || !detailsEnabled()) return null
            if (packet.size < 41 || (packet[0].toInt() ushr 4 and 15) != 6 ||
                packet[6].toInt() and 255 != 58) return null
            val kind = when (packet[40].toInt() and 255) { 135 -> "ns"; 136 -> "na"; else -> return null }
            val length = ((packet[4].toInt() and 255) shl 8) or (packet[5].toInt() and 255)
            val complete = length >= 24 && length + 40 <= packet.size
            val shape = when {
                length + 40 > packet.size -> "truncated"
                !complete || packet[41].toInt() != 0 -> "invalid"
                else -> "complete"
            }
            val source = if ((8 until 24).all { packet[it] == 0.toByte() }) "unspecified" else "specified"
            val hop = if (packet[7].toInt() and 255 == 255) "valid" else "invalid"
            val checksum = if (!complete) "unknown" else if (validNeighborChecksum(packet, length)) "valid" else "invalid"
            val target = when {
                !complete || hostIpv6?.size != 16 -> "unknown"
                (0 until 16).all { packet[48 + it] == hostIpv6[it] } -> "host"
                else -> "other"
            }
            neighborReceipts++
            Neighbor(neighborReceipts, "icmp=$kind shape=$shape source=$source target=$target hopLimit=$hop checksum=$checksum")
        }
    }

    fun completeNeighbor(neighbor: Neighbor?, written: Boolean) {
        if (neighbor == null || !detailsEnabled()) return
        synchronized(this) { if (!detailsEnabled()) return }
        val result = if (written) "written" else "failed"
        runCatching { sink("USB network stage=nd-receipt receipt=${neighbor.receipt} ${neighbor.fields} tunWrite=$result") }
    }

    // Only a direct, complete ICMPv6 payload is examined; no extension or option traversal/copies.
    // U16 payload length bounds work to 65535 bytes, with constant storage and no retained bytes.
    private fun validNeighborChecksum(packet: ByteArray, length: Int): Boolean {
        var sum = length.toLong() + 58
        for (offset in 8 until 40 step 2)
            sum += ((packet[offset].toInt() and 255) shl 8) or (packet[offset + 1].toInt() and 255)
        val end = 40 + length
        var offset = 40
        while (offset < end) {
            sum += (packet[offset].toInt() and 255) shl 8
            if (offset + 1 < end) sum += packet[offset + 1].toInt() and 255
            offset += 2
        }
        while (sum ushr 16 != 0L) sum = (sum and 65535) + (sum ushr 16)
        return sum == 65535L
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
