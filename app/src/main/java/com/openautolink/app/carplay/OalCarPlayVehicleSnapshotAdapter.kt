package com.openautolink.app.carplay

import android.os.SystemClock
import com.openautolink.app.input.ProcessVehicleDataRuntime
import com.openautolink.app.transport.ControlMessage
import com.openautolink.carplaylab.intake.OalEvSnapshot
import com.openautolink.carplaylab.intake.OalCarDataCapabilities
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** Read-only process StateFlow adapter. Does not attach/replace AA's single projection consumer. */
class OalCarPlayVehicleSnapshotAdapter(
    private val source: () -> StateFlow<ControlMessage.VehicleData>? = ProcessVehicleDataRuntime::latestVehicleData,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lastDiagnostics = mutableMapOf<Pair<String,String>,Long>()
    fun snapshot(): OalEvSnapshot? = snapshot {}

    /** One source read freezes both the identification metadata and implemented live fields. */
    @Synchronized fun capabilities(location: Boolean, receipt: (String) -> Unit = {}): OalCarDataCapabilities {
        val data = source()?.value
        val initial = snapshot(data, receipt)
        val electric = data?.fuelTypes?.toSet() == setOf(10)
        fun label(raw: String?): String? = raw?.takeIf {
            it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 80 &&
                it.none { c -> c.isISOControl() || c.isSurrogate() }
        }?.trim()?.takeUnless { it.lowercase(java.util.Locale.ROOT) in setOf("unknown","null","n/a") }
        val make = label(data?.carMake)
        val model = label(data?.carModel)
        val year = data?.carYear?.takeIf { it.matches(Regex("[1-9][0-9]{3}")) }
        val name = if (electric && (make != null || model != null)) listOfNotNull(year,make,model).joinToString(" ") else null
        // INFO_EV_CONNECTOR_TYPE uses VHAL ordinals, NOT deprecated CarInfoManager ordinals.
        // Only mappings independently supported by both contracts; no Tesla/NACS assumptions.
        val connectors = if (electric) data?.evConnectorTypes.orEmpty().fold(0) { mask, vhal ->
            val wire = when (vhal) { 1 -> 2; 2 -> 4; 5 -> 0; 6 -> 1; 10 -> 6; 11 -> 5; else -> null }
            mask or (wire?.let { 1 shl it } ?: 0)
        } else 0
        return OalCarDataCapabilities(location, initial?.rangeKm != null,
            initial?.currentChargeWh != null, initial?.maxChargeWh != null, name, connectors,
            outsideTemperature = initial?.outsideTemperatureC != null)
    }

    /** Stable HAL modes describe value changes, not heartbeat cadence. Receipt liveness stays bounded. */
    @Synchronized fun snapshot(receipt: (String) -> Unit): OalEvSnapshot? {
        return snapshot(source()?.value, receipt)
    }

    private fun snapshot(data: ControlMessage.VehicleData?, receipt: (String) -> Unit): OalEvSnapshot? {
        val now = elapsedMs()
        val generation = data?.vhalRegistrationGeneration
        fun age(timestampMs: Long?): Long = if (timestampMs == null || timestampMs < 0 || now < timestampMs) -1
            else (now-timestampMs).coerceAtMost(99_999_999)
        fun reason(raw: Float?, property: String, limit: Double, positive: Boolean=false, minimum: Double=0.0): String {
            if (raw == null) return "absent"
            if (data?.fuelTypes?.toSet() != setOf(10)) return "fuel"
            val observation = data.evObservationMetadata[property] ?: return "metadata"
            if (generation == null || generation <= 0 || observation.registrationGeneration != generation) return "generation"
            if (observation.source != "vhal") return "source"
            if (!observation.subscriptionActive) return "inactive"
            if (observation.status != 0) return "status"
            val timestamp = observation.timestampElapsedNanos ?: return "timestamp"
            if (timestamp <= 0 || now < timestamp/1_000_000) return "timestamp"
            // An old event time is legitimate for a confirmed unchanged value. Only an actual
            // discovered STATIC/ONCHANGE config grants this exception; requested sample rate does not.
            if (observation.changeMode !in setOf(0, 1) && now-timestamp/1_000_000 > 30_000) return "source_age"
            if (now < observation.receivedElapsedMs || observation.receivedElapsedMs < 0 || now-observation.receivedElapsedMs > 30_000) return "receipt_age"
            if (!raw.isFinite() || raw.toDouble() < minimum || raw.toDouble() > limit || (positive && raw <= 0)) return "bounds"
            return "available"
        }
        val rangeReason = reason(data?.rangeKm,"RANGE_REMAINING",65535.0)
        val temperatureReason = reason(data?.ambientTempC,"ENV_OUTSIDE_TEMPERATURE",32767.0,minimum=-32768.0)
        val temperature = data?.ambientTempC?.takeIf {temperatureReason=="available"}?.roundToInt()
        val maxReason = reason(data?.evCurrentBatteryCapacityWh,"EV_CURRENT_BATTERY_CAPACITY",4294967295.0,true)
        var currentReason = reason(data?.evBatteryLevelWh,"EV_BATTERY_LEVEL",4294967295.0)
        val range = data?.rangeKm?.takeIf {rangeReason=="available"}?.toInt()
        val max = data?.evCurrentBatteryCapacityWh?.takeIf {maxReason=="available"}?.toLong()
        var current = data?.evBatteryLevelWh?.takeIf {currentReason=="available"}?.toLong()
        if (current != null && max != null && current > max) { current=null; currentReason="inconsistent" }
        fun report(label: String,property: String,result: String) {
            val key=label to result
            val last=lastDiagnostics[key]
            if (last != null && now >= last && now-last < 1000) return
            lastDiagnostics[key]=now
            val observation=data?.evObservationMetadata?.get(property)
            val status=observation?.status?.takeIf {it in 0..9} ?: -1
            val sameGeneration=generation != null && generation>0 && observation?.registrationGeneration==generation
            val sourceAge=age(observation?.timestampElapsedNanos?.takeIf {it>0}?.div(1_000_000))
            val receiptAge=age(observation?.receivedElapsedMs)
            runCatching {receipt("carplay-car-data-source property=$label reason=$result status=$status active=${observation?.subscriptionActive==true} generation=$sameGeneration sourceAgeMs=$sourceAge receiptAgeMs=$receiptAge")}
        }
        report("range","RANGE_REMAINING",rangeReason)
        report("current","EV_BATTERY_LEVEL",currentReason)
        report("maximum","EV_CURRENT_BATTERY_CAPACITY",maxReason)
        report("temperature","ENV_OUTSIDE_TEMPERATURE",temperatureReason)
        if (range == null && current == null && max == null && temperature == null) return null
        // Actual usable capacity only. Nominal/new capacity, derived displayed SOC and inferred
        // charging state are not substitutes. No vehicle profile or Maps eligibility is implied.
        return OalEvSnapshot(range,current,max,temperature)
    }
}
