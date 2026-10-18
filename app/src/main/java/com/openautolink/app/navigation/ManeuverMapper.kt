package com.openautolink.app.navigation

/**
 * Maps bridge maneuver strings to ManeuverType enum values.
 * Bridge sends maneuver names from aasdk's NavigationStatusMessage,
 * which originate from the phone's Android Auto navigation app.
 */
object ManeuverMapper {

    /**
     * Map a maneuver string from the bridge to a ManeuverType.
     * Handles normalized names emitted by both native AA wire vocabularies.
     * Bare numeric codes are intentionally not accepted: legacy and modern
     * enum namespaces reuse numbers for different maneuvers.
     */
    fun fromWire(maneuver: String?): ManeuverType {
        if (maneuver == null) return ManeuverType.UNKNOWN

        return when (maneuver.lowercase().trim()) {
            // Standard direction-based names (OAL protocol)
            "depart" -> ManeuverType.DEPART
            "straight", "head", "continue" -> ManeuverType.STRAIGHT
            "turn_slight_left", "slight_left", "slight_turn_left" -> ManeuverType.TURN_SLIGHT_LEFT
            "turn_left", "left" -> ManeuverType.TURN_LEFT
            "turn_sharp_left", "sharp_left", "sharp_turn_left" -> ManeuverType.TURN_SHARP_LEFT
            "turn_slight_right", "slight_right", "slight_turn_right" -> ManeuverType.TURN_SLIGHT_RIGHT
            "turn_right", "right" -> ManeuverType.TURN_RIGHT
            "turn_sharp_right", "sharp_right", "sharp_turn_right" -> ManeuverType.TURN_SHARP_RIGHT
            "u_turn_left", "uturn_left" -> ManeuverType.U_TURN_LEFT
            "u_turn_right", "uturn_right" -> ManeuverType.U_TURN_RIGHT
            "keep_left" -> ManeuverType.KEEP_LEFT
            "keep_right" -> ManeuverType.KEEP_RIGHT
            "merge_left" -> ManeuverType.MERGE_LEFT
            "merge_right" -> ManeuverType.MERGE_RIGHT
            "merge_unspecified" -> ManeuverType.MERGE_UNSPECIFIED
            "fork_left" -> ManeuverType.FORK_LEFT
            "fork_right" -> ManeuverType.FORK_RIGHT
            "on_ramp_left", "ramp_left" -> ManeuverType.ON_RAMP_LEFT
            "on_ramp_right", "ramp_right" -> ManeuverType.ON_RAMP_RIGHT
            "on_ramp_slight_left" -> ManeuverType.ON_RAMP_SLIGHT_LEFT
            "on_ramp_slight_right" -> ManeuverType.ON_RAMP_SLIGHT_RIGHT
            "on_ramp_sharp_left" -> ManeuverType.ON_RAMP_SHARP_LEFT
            "on_ramp_sharp_right" -> ManeuverType.ON_RAMP_SHARP_RIGHT
            "on_ramp_u_turn_left" -> ManeuverType.ON_RAMP_U_TURN_LEFT
            "on_ramp_u_turn_right" -> ManeuverType.ON_RAMP_U_TURN_RIGHT
            "off_ramp_left" -> ManeuverType.OFF_RAMP_LEFT
            "off_ramp_right" -> ManeuverType.OFF_RAMP_RIGHT
            "off_ramp_slight_left" -> ManeuverType.OFF_RAMP_SLIGHT_LEFT
            "off_ramp_slight_right" -> ManeuverType.OFF_RAMP_SLIGHT_RIGHT
            "roundabout_enter", "roundabout" -> ManeuverType.ROUNDABOUT_ENTER
            "roundabout_exit" -> ManeuverType.ROUNDABOUT_EXIT
            "roundabout_enter_cw" -> ManeuverType.ROUNDABOUT_ENTER_CW
            "roundabout_enter_ccw" -> ManeuverType.ROUNDABOUT_ENTER_CCW
            "roundabout_exit_cw" -> ManeuverType.ROUNDABOUT_EXIT_CW
            "roundabout_exit_ccw" -> ManeuverType.ROUNDABOUT_EXIT_CCW
            // Legacy TurnSide is not a circulation enum. Even a side suffix
            // cannot establish CW/CCW; preserve the generic instruction only.
            "roundabout_enter_and_exit", "roundabout_enter_and_exit_left",
            "roundabout_enter_and_exit_right" -> ManeuverType.ROUNDABOUT_ENTER_AND_EXIT
            // Preserve representable ordinal semantics until the AA angle's
            // unit/reference convention is proven. The raw source angle is
            // retained in ManeuverState, not guessed from a name or ordinal.
            "roundabout_enter_and_exit_cw",
            "roundabout_enter_and_exit_cw_with_angle" -> ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CW
            "roundabout_enter_and_exit_ccw",
            "roundabout_enter_and_exit_ccw_with_angle" -> ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CCW
            "destination", "arrive" -> ManeuverType.DESTINATION
            "destination_straight" -> ManeuverType.DESTINATION_STRAIGHT
            "destination_left" -> ManeuverType.DESTINATION_LEFT
            "destination_right" -> ManeuverType.DESTINATION_RIGHT
            "ferry", "ferry_boat" -> ManeuverType.FERRY
            "ferry_train" -> ManeuverType.FERRY_TRAIN
            "name_change", "new_name" -> ManeuverType.NAME_CHANGE
            else -> ManeuverType.UNKNOWN
        }
    }

    /**
     * Get a Unicode arrow character for the maneuver type,
     * useful for simple text-based display.
     */
    fun toArrow(type: ManeuverType): String = when (type) {
        ManeuverType.STRAIGHT -> "↑"
        ManeuverType.TURN_SLIGHT_LEFT -> "↖"
        ManeuverType.TURN_LEFT -> "←"
        ManeuverType.TURN_SHARP_LEFT -> "↙"
        ManeuverType.TURN_SLIGHT_RIGHT -> "↗"
        ManeuverType.TURN_RIGHT -> "→"
        ManeuverType.TURN_SHARP_RIGHT -> "↘"
        ManeuverType.U_TURN_LEFT -> "⤺"
        ManeuverType.U_TURN_RIGHT -> "⤻"
        ManeuverType.KEEP_LEFT -> "↖"
        ManeuverType.KEEP_RIGHT -> "↗"
        ManeuverType.MERGE_LEFT -> "↰"
        ManeuverType.MERGE_UNSPECIFIED -> "•"
        ManeuverType.MERGE_RIGHT -> "↱"
        ManeuverType.FORK_LEFT -> "⑂"
        ManeuverType.FORK_RIGHT -> "⑂"
        ManeuverType.ON_RAMP_LEFT, ManeuverType.ON_RAMP_SLIGHT_LEFT,
        ManeuverType.ON_RAMP_SHARP_LEFT, ManeuverType.ON_RAMP_U_TURN_LEFT -> "↰"
        ManeuverType.ON_RAMP_RIGHT, ManeuverType.ON_RAMP_SLIGHT_RIGHT,
        ManeuverType.ON_RAMP_SHARP_RIGHT, ManeuverType.ON_RAMP_U_TURN_RIGHT -> "↱"
        ManeuverType.OFF_RAMP_LEFT, ManeuverType.OFF_RAMP_SLIGHT_LEFT -> "↰"
        ManeuverType.OFF_RAMP_RIGHT, ManeuverType.OFF_RAMP_SLIGHT_RIGHT -> "↱"
        ManeuverType.ROUNDABOUT_ENTER, ManeuverType.ROUNDABOUT_EXIT,
        ManeuverType.ROUNDABOUT_ENTER_AND_EXIT -> "○"
        ManeuverType.ROUNDABOUT_ENTER_CW, ManeuverType.ROUNDABOUT_EXIT_CW,
        ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CW -> "↻"
        ManeuverType.ROUNDABOUT_ENTER_CCW, ManeuverType.ROUNDABOUT_EXIT_CCW,
        ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CCW -> "↺"
        ManeuverType.DESTINATION, ManeuverType.DESTINATION_STRAIGHT,
        ManeuverType.DESTINATION_LEFT, ManeuverType.DESTINATION_RIGHT -> "⚑"
        ManeuverType.DEPART -> "▶"
        ManeuverType.FERRY, ManeuverType.FERRY_TRAIN -> "⛴"
        else -> "•"
    }
}
