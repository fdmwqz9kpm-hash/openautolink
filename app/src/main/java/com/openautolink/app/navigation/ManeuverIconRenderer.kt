package com.openautolink.app.navigation

import androidx.annotation.DrawableRes
import com.openautolink.app.R

/** Original OAL vector fallback. Geographic directions never follow the UI's RTL locale. */
object ManeuverIconRenderer {
    @DrawableRes
    fun drawableForManeuver(type: ManeuverType): Int = when (type) {
        ManeuverType.UNKNOWN -> R.drawable.nav_unknown
        ManeuverType.DEPART -> R.drawable.nav_depart
        ManeuverType.STRAIGHT -> R.drawable.nav_straight
        ManeuverType.TURN_SLIGHT_LEFT -> R.drawable.nav_turn_slight_left
        ManeuverType.TURN_LEFT -> R.drawable.nav_turn_left
        ManeuverType.TURN_SHARP_LEFT -> R.drawable.nav_turn_sharp_left
        ManeuverType.TURN_SLIGHT_RIGHT -> R.drawable.nav_turn_slight_right
        ManeuverType.TURN_RIGHT -> R.drawable.nav_turn_right
        ManeuverType.TURN_SHARP_RIGHT -> R.drawable.nav_turn_sharp_right
        ManeuverType.U_TURN_LEFT -> R.drawable.nav_u_turn_left
        ManeuverType.U_TURN_RIGHT -> R.drawable.nav_u_turn_right
        ManeuverType.KEEP_LEFT -> R.drawable.nav_keep_left
        ManeuverType.KEEP_RIGHT -> R.drawable.nav_keep_right
        ManeuverType.MERGE_LEFT -> R.drawable.nav_merge_left
        ManeuverType.MERGE_RIGHT -> R.drawable.nav_merge_right
        ManeuverType.MERGE_UNSPECIFIED -> R.drawable.nav_merge_unspecified
        ManeuverType.FORK_LEFT -> R.drawable.nav_fork_left
        ManeuverType.FORK_RIGHT -> R.drawable.nav_fork_right
        ManeuverType.ON_RAMP_LEFT -> R.drawable.nav_on_ramp_left
        ManeuverType.ON_RAMP_RIGHT -> R.drawable.nav_on_ramp_right
        ManeuverType.ON_RAMP_SLIGHT_LEFT -> R.drawable.nav_on_ramp_slight_left
        ManeuverType.ON_RAMP_SLIGHT_RIGHT -> R.drawable.nav_on_ramp_slight_right
        ManeuverType.ON_RAMP_SHARP_LEFT -> R.drawable.nav_on_ramp_sharp_left
        ManeuverType.ON_RAMP_SHARP_RIGHT -> R.drawable.nav_on_ramp_sharp_right
        ManeuverType.ON_RAMP_U_TURN_LEFT -> R.drawable.nav_on_ramp_u_turn_left
        ManeuverType.ON_RAMP_U_TURN_RIGHT -> R.drawable.nav_on_ramp_u_turn_right
        ManeuverType.OFF_RAMP_LEFT -> R.drawable.nav_off_ramp_left
        ManeuverType.OFF_RAMP_RIGHT -> R.drawable.nav_off_ramp_right
        ManeuverType.OFF_RAMP_SLIGHT_LEFT -> R.drawable.nav_off_ramp_slight_left
        ManeuverType.OFF_RAMP_SLIGHT_RIGHT -> R.drawable.nav_off_ramp_slight_right
        ManeuverType.ROUNDABOUT_ENTER, ManeuverType.ROUNDABOUT_EXIT,
        ManeuverType.ROUNDABOUT_ENTER_AND_EXIT -> R.drawable.nav_roundabout_neutral
        ManeuverType.ROUNDABOUT_ENTER_CW -> R.drawable.nav_roundabout_enter_cw
        ManeuverType.ROUNDABOUT_ENTER_CCW -> R.drawable.nav_roundabout_enter_ccw
        ManeuverType.ROUNDABOUT_EXIT_CW -> R.drawable.nav_roundabout_exit_cw
        ManeuverType.ROUNDABOUT_EXIT_CCW -> R.drawable.nav_roundabout_exit_ccw
        ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CW -> R.drawable.nav_roundabout_cw
        ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CCW -> R.drawable.nav_roundabout_ccw
        ManeuverType.DESTINATION -> R.drawable.nav_destination
        ManeuverType.DESTINATION_STRAIGHT -> R.drawable.nav_destination_straight
        ManeuverType.DESTINATION_LEFT -> R.drawable.nav_destination_left
        ManeuverType.DESTINATION_RIGHT -> R.drawable.nav_destination_right
        ManeuverType.FERRY -> R.drawable.nav_ferry
        ManeuverType.FERRY_TRAIN -> R.drawable.nav_ferry_train
        ManeuverType.NAME_CHANGE -> R.drawable.nav_name_change
    }
}
