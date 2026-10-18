package com.openautolink.app.carplay

import android.os.SystemClock
import com.openautolink.app.input.ProcessVehicleDataRuntime
import com.openautolink.app.transport.ControlMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Ordinary CarPlay UI policy, NOT a gear packet or a parked-video capability.
 * In real mode only the exact active AVAILABLE GEAR_SELECTION Int Park releases the main UI.
 * A separately persisted CarPlay parked-use override releases only this policy, not raw gear.
 * The secondary display remains restricted. No AA override/derived driving input is consulted.
 * A real ONCHANGE status read can confirm an unchanged old event; never retimestamp that event.
 * 15 seconds is a local fail-closed liveness budget for the existing five-second Binder lane.
 * A timer revokes expired Park even when the entire upstream StateFlow becomes silent.
 * Revoke is nonblocking. Retirement waits for an admitted write, never acknowledges a timeout.
 */
class CarPlayDrivingRestrictionBridge internal constructor(
    private val source: StateFlow<ControlMessage.VehicleData>?,
    dispatcher: CoroutineDispatcher,
    private val elapsedMs: () -> Long,
    private val maxAgeMs: Long,
    private val alwaysInPark: Boolean = false,
) {
    constructor() : this(ProcessVehicleDataRuntime.latestVehicleData(), Dispatchers.IO,
        SystemClock::elapsedRealtime, 15_000L)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val gate = Mutex()
    private val closed = AtomicBoolean()
    private var active: Owner? = null
    class Owner internal constructor() {
        internal val revoked = AtomicBoolean()
        internal var job: Job? = null
    }
    internal fun permitsUnlock(): Boolean = !closed.get() && active?.revoked?.get() == false &&
        (alwaysInPark || restrictionReason(source?.value) == "actual_park")
    internal fun restrictionReason(data: ControlMessage.VehicleData?, now: Long = elapsedMs()): String {
        if (data == null) return "absent"
        val o = data.evObservationMetadata["GEAR_SELECTION"] ?: return "metadata"
        if (o.source != "vhal" || o.propertyId != 0x11400400) return "source"
        if (data.vhalRegistrationGeneration == null || data.vhalRegistrationGeneration <= 0 ||
            o.registrationGeneration != data.vhalRegistrationGeneration) return "generation"
        if (!o.subscriptionActive) return "inactive"
        if (o.status != 0) return "status"
        val at = o.timestampElapsedNanos ?: return "timestamp"
        if (at <= 0 || at / 1_000_000 > now || o.receivedElapsedMs < 0 || o.receivedElapsedMs > now) return "timestamp"
        val read = o.statusReadElapsedMs
        val liveRead = o.changeMode == 1 && read != null && read == o.receivedElapsedMs &&
            read <= now && now - read <= maxAgeMs
        if (now - o.receivedElapsedMs > maxAgeMs || (!liveRead && now - at / 1_000_000 > maxAgeMs)) return "stale"
        if (data.gearRaw != 4) return "not_park"
        return "actual_park"
    }
    internal suspend fun replace(ready: StateFlow<Boolean>, send: (Boolean) -> Boolean,
        receipt: (String) -> Unit = {}): Owner = gate.withLock {
        check(!closed.get())
        active?.let { revoke(it); it.job?.cancel() }
        val owner = Owner()
        active = owner
        owner.job = scope.launch {
            var delivered: Boolean? = null
            var wasReady = false
            var attempted: Pair<Boolean, Long?>? = null
            var writes = 0L
            var budget = 64
            val data = source ?: MutableStateFlow(ControlMessage.VehicleData())
            val clock = flow { while (currentCoroutineContext().isActive) { emit(Unit); delay(250) } }
            combine(data, ready, clock) { _, _, _ -> Unit }.collect {
                gate.withLock {
                    if (active !== owner || owner.revoked.get() || closed.get()) return@withLock
                    if (!ready.value) {
                        wasReady = false; delivered = null; attempted = null
                        return@withLock
                    }
                    if (!wasReady) { delivered = null; attempted = null; wasReady = true }
                    val snapshot = source?.value
                    val reason = if (alwaysInPark) "user_override" else restrictionReason(snapshot)
                    val limited = reason != "actual_park" && reason != "user_override"
                    val key = limited to snapshot?.evObservationMetadata?.get("GEAR_SELECTION")?.sequence
                    if (delivered == limited || attempted == key) return@withLock
                    attempted = key
                    val sent = runCatching { send(limited) }.getOrDefault(false)
                    // An admitted write may finish after revoke; suppress its late receipts.
                    if (owner.revoked.get() || closed.get()) return@withLock
                    if (sent) { delivered = limited; if (writes < Long.MAX_VALUE) writes++ }
                    if (budget > 0) {
                        budget--
                        runCatching {
                            receipt("carplay-restriction reason=$reason limited=$limited result=${if (sent) "transmitted" else "send_failed"} count=$writes")
                        }
                    }
                }
            }
        }
        owner
    }
    fun revoke(owner: Owner) { owner.revoked.set(true) }
    suspend fun retire(owner: Owner) {
        revoke(owner)
        gate.withLock {
            if (active === owner) { active = null; owner.job?.cancel() }
        }
        owner.job?.join()
    }
    suspend fun close() {
        closed.set(true)
        val owner = gate.withLock { active.also { active = null; it?.job?.cancel() } }
        owner?.let { revoke(it); it.job?.join() }
        scope.cancel()
    }
}
