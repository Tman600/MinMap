package com.example.mapstyleeditor.car

import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import com.example.mapstyleeditor.nav.RouteStep
import com.example.mapstyleeditor.nav.usesMiles
import kotlin.math.roundToInt

/**
 * The car's maneuver type for a Mapbox step (type + modifier), which Android Auto uses for its turn
 * card and the driver's cluster. Roundabouts turn clockwise where traffic drives on the left.
 */
fun carManeuverType(step: RouteStep): Int {
    val m = step.modifier.orEmpty()
    val left = "left" in m
    val right = "right" in m
    val clockwise = step.drivingSide == "left"
    return when (step.type) {
        "depart" -> Maneuver.TYPE_DEPART
        "arrive" -> when {
            left -> Maneuver.TYPE_DESTINATION_LEFT
            right -> Maneuver.TYPE_DESTINATION_RIGHT
            m == "straight" -> Maneuver.TYPE_DESTINATION_STRAIGHT
            else -> Maneuver.TYPE_DESTINATION
        }
        "roundabout", "rotary", "roundabout turn" -> when {
            step.exit == null -> if (clockwise) Maneuver.TYPE_ROUNDABOUT_ENTER_CW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
            clockwise -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
            else -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
        }
        "exit roundabout", "exit rotary" -> if (clockwise) Maneuver.TYPE_ROUNDABOUT_EXIT_CW else Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
        "fork" -> when {
            left -> Maneuver.TYPE_FORK_LEFT
            right -> Maneuver.TYPE_FORK_RIGHT
            else -> Maneuver.TYPE_STRAIGHT
        }
        "merge" -> when {
            left -> Maneuver.TYPE_MERGE_LEFT
            right -> Maneuver.TYPE_MERGE_RIGHT
            else -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
        }
        "on ramp" -> when (m) {
            "slight left" -> Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT
            "left" -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
            "sharp left" -> Maneuver.TYPE_ON_RAMP_SHARP_LEFT
            "slight right" -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT
            "sharp right" -> Maneuver.TYPE_ON_RAMP_SHARP_RIGHT
            "uturn" -> if (clockwise) Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT else Maneuver.TYPE_ON_RAMP_U_TURN_LEFT
            else -> if (left) Maneuver.TYPE_ON_RAMP_NORMAL_LEFT else Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
        }
        "off ramp" -> when (m) {
            "slight left" -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT
            "left", "sharp left" -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
            "right", "sharp right" -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            else -> if (left) Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT else Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
        }
        else -> when (m) {
            // Continue, turn, end of road, new name: the modifier says which way.
            "uturn" -> if (clockwise) Maneuver.TYPE_U_TURN_RIGHT else Maneuver.TYPE_U_TURN_LEFT
            "sharp left" -> Maneuver.TYPE_TURN_SHARP_LEFT
            "left" -> Maneuver.TYPE_TURN_NORMAL_LEFT
            "slight left" -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            "sharp right" -> Maneuver.TYPE_TURN_SHARP_RIGHT
            "right" -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            "slight right" -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            "straight" -> if (step.type == "new name") Maneuver.TYPE_NAME_CHANGE else Maneuver.TYPE_STRAIGHT
            else -> if (step.type == "new name" || step.type == "continue") Maneuver.TYPE_STRAIGHT else Maneuver.TYPE_UNKNOWN
        }
    }
}

/** Whether [type] needs a roundabout exit number (Android Auto refuses those maneuvers without one). */
fun needsExitNumber(type: Int) =
    type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW || type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW

/** A distance as the car shows it: feet then miles (one decimal under 10) or meters then km. */
fun carDistance(meters: Double): Distance = if (usesMiles()) {
    val miles = meters / 1609.344
    when {
        miles < 0.1 -> Distance.create(((meters / 0.3048 / 50).roundToInt().coerceAtLeast(1) * 50).toDouble(), Distance.UNIT_FEET)
        miles < 10 -> Distance.create(miles, Distance.UNIT_MILES_P1)
        else -> Distance.create(miles.roundToInt().toDouble(), Distance.UNIT_MILES)
    }
} else {
    when {
        meters < 1000 -> Distance.create(((meters / 10).roundToInt().coerceAtLeast(1) * 10).toDouble(), Distance.UNIT_METERS)
        meters < 10_000 -> Distance.create(meters / 1000, Distance.UNIT_KILOMETERS_P1)
        else -> Distance.create((meters / 1000).roundToInt().toDouble(), Distance.UNIT_KILOMETERS)
    }
}
