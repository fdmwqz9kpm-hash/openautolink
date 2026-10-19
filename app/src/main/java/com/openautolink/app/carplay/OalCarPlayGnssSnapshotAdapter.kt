package com.openautolink.app.carplay

import com.openautolink.app.input.GnssForwarder
import com.openautolink.app.transport.ControlMessage

class OalCarPlayGnssSnapshotAdapter(
    private val elapsedMs: () -> Long,
    private val requireSourceTime: Boolean = false,
    private val unixMs: () -> Long = System::currentTimeMillis,
    private val createSource: ((ControlMessage.Gnss) -> Unit) -> GnssForwarder,
) : AutoCloseable {
    private var cache = com.openautolink.carplaylab.intake.OalNmeaCache(elapsedMs)
    private var source: GnssForwarder? = null
    private var active = false
    private var retired = false
    private var generation = 0L
    private var clockOffsetMs: Long? = null

    private fun clearCache() {
        cache = com.openautolink.carplaylab.intake.OalNmeaCache(elapsedMs)
        clockOffsetMs = null
    }

    /** Compare Unix to Unix, then accumulate queue/cache age with the monotonic clock. */
    private fun accept(message: ControlMessage.Gnss) {
        if (!requireSourceTime) { cache.accept(message.nmea); return }
        val age = runCatching {
            val fix = requireNotNull(message.fixUnixMs)
            val wall = requireNotNull(message.receivedUnixMs)
            val received = requireNotNull(message.receivedElapsedMs)
            val now = elapsedMs()
            require(fix > 0 && wall >= fix && received >= 0 && now >= received)
            val offset = Math.subtractExact(unixMs(), now)
            val receiptOffset = Math.subtractExact(wall, received)
            require(Math.subtractExact(offset, receiptOffset) in -1000..1000)
            clockOffsetMs?.let { previous ->
                if (Math.subtractExact(offset, previous) !in -1000..1000) clearCache()
            }
            val age = Math.addExact(Math.subtractExact(wall, fix), Math.subtractExact(now, received))
            require(age in 0..3000)
            clockOffsetMs = offset
            age
        }.getOrNull()
        if (age == null) clearCache() else cache.acceptWithSourceAge(message.nmea, age)
    }

    /** Caller holds this adapter's monitor. Do not lose an owner whose OS removal failed. */
    private fun retireSource() {
        val owner=source ?: return
        owner.stop()
        if (source===owner) source=null
    }

    @Synchronized fun setActive(requestedActive: Boolean) {
        if (retired || (requestedActive == active && (active || source == null))) return
        generation++
        active = false
        clearCache()
        retireSource()
        if (!requestedActive) return
        active=true
        val ownerGeneration = generation
        val owner = try { createSource { message ->
            synchronized(this) {
                if (active && !retired && ownerGeneration == generation) accept(message)
            }
        } } catch (failure: Exception) {
            active=false;generation++;throw failure
        }
        source = owner
        try {
            owner.start()
            if (!owner.isActive) { active=false;generation++;retireSource() }
        } catch (failure: Exception) {
            active=false;generation++
            try {retireSource()} catch (cleanup: Exception) {failure.addSuppressed(cleanup)}
            throw failure // Reporter catches without exporting text; failed exact owner stays retained.
        }
    }

    @Synchronized fun latest(requested: Set<Int>): String? {
        if (!active || retired) return null
        if (requireSourceTime) {
            val previous = clockOffsetMs ?: return null
            val consistent = runCatching { Math.subtractExact(Math.subtractExact(unixMs(), elapsedMs()), previous) in -1000..1000 }
                .getOrDefault(false)
            if (!consistent) { clearCache(); return null }
        }
        return cache.latest(requested)
    }

    @Synchronized override fun close() {
        if (!retired) {
            retired=true;active=false;generation++
            clearCache()
        }
        retireSource() // Retry a failed removal even after the permanent callback fence is set.
    }
}
