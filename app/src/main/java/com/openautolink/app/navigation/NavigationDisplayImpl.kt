package com.openautolink.app.navigation

import com.openautolink.app.transport.ControlMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Processes nav_state control messages from the bridge and maintains
 * the current maneuver state for display in UI or cluster service.
 */
class NavigationDisplayImpl : NavigationDisplay {

    private val _currentManeuver = MutableStateFlow<ManeuverState?>(null)
    override val currentManeuver: StateFlow<ManeuverState?> = _currentManeuver.asStateFlow()

    override fun onNavState(state: ControlMessage.NavState) {
        // Turn events and distance events arrive as separate JNI callbacks with
        // partial fields.  Merge incoming fields onto the previous ManeuverState
        // so the cluster (and UI) always sees a complete snapshot.
        val prev = _currentManeuver.value

        val type = if (state.maneuver != null) ManeuverMapper.fromWire(state.maneuver) else prev?.type ?: ManeuverType.UNKNOWN
        val newManeuver = state.maneuver != null
        val distanceMeters = state.distanceMeters ?: prev?.distanceMeters.takeUnless { newManeuver }
        val road = state.road ?: prev?.roadName.takeUnless { newManeuver }
        val etaSeconds = state.etaSeconds ?: prev?.etaSeconds.takeUnless { newManeuver }
        // An explicit turn event starts a new graphic/ordinal snapshot even if
        // its enum repeats. Only distance/ETA deltas may retain old turn data.
        val navImage = state.navImageBase64 ?: prev?.navImageBase64.takeUnless { newManeuver }
        val cue = state.cue ?: prev?.cue.takeUnless { newManeuver }
        val roundaboutExit = state.roundaboutExitNumber ?: prev?.roundaboutExitNumber.takeUnless { newManeuver }
        val currentRoad = state.currentRoad ?: prev?.currentRoad
        val destination = state.destination ?: prev?.destination
        val etaFormatted = state.etaFormatted ?: prev?.etaFormatted
        // A formatted value and its unit are one source observation. New raw
        // distance or an explicit turn invalidates the previous pair; a partial
        // pair must never combine with old units/value from another observation.
        val hasDistanceUpdate = state.distanceMeters != null || state.displayDistance != null || state.displayDistanceUnit != null
        val completeDisplayPair = !state.displayDistance.isNullOrEmpty() && !state.displayDistanceUnit.isNullOrEmpty()
        val retainDisplayPair = !newManeuver && !hasDistanceUpdate
        val displayDistance = if (completeDisplayPair) state.displayDistance else prev?.displayDistance.takeIf { retainDisplayPair }
        val displayDistanceUnit = if (completeDisplayPair) state.displayDistanceUnit else prev?.displayDistanceUnit.takeIf { retainDisplayPair }
        val timeToArrival = state.timeToArrivalSeconds ?: prev?.timeToArrivalSeconds
        val destDistMeters = state.destDistanceMeters ?: prev?.destDistanceMeters
        val destDistDisplay = state.destDistanceDisplay ?: prev?.destDistanceDisplay
        val destDistUnit = state.destDistanceUnit ?: prev?.destDistanceUnit

        // Use pre-formatted distance from bridge if available, else format locally
        val formattedDist = if (!displayDistance.isNullOrEmpty() && !displayDistanceUnit.isNullOrEmpty()) {
            "$displayDistance ${DistanceFormatter.unitLabel(displayDistanceUnit)}"
        } else {
            DistanceFormatter.format(distanceMeters)
        }

        val lanes = state.lanes?.map { lane ->
            LaneInfo(lane.directions.map { dir ->
                LaneDirectionInfo(dir.shape, dir.highlighted)
            })
        } ?: prev?.lanes.takeUnless { newManeuver }

        _currentManeuver.value = ManeuverState(
            type = type,
            distanceMeters = distanceMeters,
            formattedDistance = formattedDist,
            roadName = road,
            etaSeconds = etaSeconds,
            navImageBase64 = navImage,
            lanes = lanes,
            cue = cue,
            roundaboutExitNumber = roundaboutExit,
            roundaboutExitAngle = state.roundaboutExitAngle ?: prev?.roundaboutExitAngle.takeUnless { newManeuver },
            currentRoad = currentRoad,
            destination = destination,
            etaFormatted = etaFormatted,
            displayDistance = displayDistance,
            displayDistanceUnit = displayDistanceUnit,
            timeToArrivalSeconds = timeToArrival,
            destDistanceMeters = destDistMeters,
            destDistanceDisplay = destDistDisplay,
            destDistanceUnit = destDistUnit
        )
    }

    override fun clear() {
        _currentManeuver.value = null
    }
}
