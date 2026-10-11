package com.example.mapstyleeditor.starbase

import com.mapbox.geojson.Point
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Starbase's two orbital launch pads as 3D models: each launch tower with its chopsticks carriage and
 * arms, the ship quick-disconnect arm, and the launch mount.
 *
 * Positions are worked out in the models' own frame (from munim-maps-vehicles: y up, the tower at
 * the origin facing +Z, the mount 22 m out) and then turned to each pad's real heading. Mapbox draws
 * a model's +Z towards the south with no rotation, takes its offset as metres [east, south, up] and
 * turns it clockwise (seen from above) for positive rotation.
 */

/** Starbase's orbital pads: each tower's position, and the compass bearing from it to its mount. */
enum class Pad(val tower: Point, val heading: Double) {
    // Positions from OpenStreetMap ("Starbase Integration Tower 1/2"); headings from each tower
    // to its launch mount there.
    PAD_1(Point.fromLngLat(-97.154736, 25.996126), 87.0),
    PAD_2(Point.fromLngLat(-97.158067, 25.997046), 183.0),
}

/** A model MinMap draws at each pad. */
enum class Part(val asset: String) {
    TOWER("tower"), CARRIAGE("carriage"), ARM_LEFT("arm-left"), ARM_RIGHT("arm-right"), QD_ARM("qd-arm"), MOUNT("mount"),
}

/** Where to draw one part: Mapbox's model translation (metres east, south, up) and heading (degrees clockwise). */
data class Pose(val east: Double, val south: Double, val up: Double, val heading: Double)

data class PlacedPart(val pad: Pad, val part: Part, val pose: Pose) {
    val key get() = "minmap-starbase-${pad.name.lowercase()}-${part.name.lowercase()}"
}

/** Every part of both pads, as they stand between launches: chopsticks parked open, QD arm swung clear. */
val STARBASE_PARTS: List<PlacedPart> = Pad.entries.flatMap { pad ->
    val padYaw = pad.heading - 180
    fun place(x: Double, y: Double, z: Double, turn: Double = 0.0) = Pose(
        east = x * cos(rad(padYaw)) - z * sin(rad(padYaw)),
        south = x * sin(rad(padYaw)) + z * cos(rad(padYaw)),
        up = y,
        heading = padYaw + turn,
    )
    listOf(
        PlacedPart(pad, Part.TOWER, place(0.0, 0.0, 0.0)),
        PlacedPart(pad, Part.CARRIAGE, place(0.0, 0.0, 0.0)),
        PlacedPart(pad, Part.ARM_LEFT, place(6.4, 90.4, 6.4)),
        PlacedPart(pad, Part.ARM_RIGHT, place(-6.4, 90.4, 6.4)),
        // With no ship on the mount, the QD arm stands swung back against the tower.
        PlacedPart(pad, Part.QD_ARM, place(5.2, 97.5, 5.2, turn = -QD_SWING_DEG)),
        PlacedPart(pad, Part.MOUNT, place(0.0, 0.0, MOUNT_Z)),
    )
}

private fun rad(deg: Double) = deg * PI / 180

/** The mount stands 22 m out from the tower. */
private const val MOUNT_Z = 22.0
private const val QD_SWING_DEG = 70.0
