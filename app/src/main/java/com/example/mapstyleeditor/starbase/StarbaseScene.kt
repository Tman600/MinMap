package com.example.mapstyleeditor.starbase

import com.mapbox.geojson.Point
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Where every piece of Starbase's two orbital pads is at a given moment: the towers, chopsticks and
 * mounts always, and around a Starship flight the whole sequence: the booster and ship rolled out,
 * stacked by the chopsticks, fuelled (frost and venting), the deluge, ignition, liftoff, staging, and
 * the booster's return to the tower or out to sea.
 *
 * Positions are worked out in the models' own frame (from munim-maps-vehicles: y up, the tower at
 * the origin facing +Z, the rocket standing 22 m out on the mount) and then turned to each pad's real
 * heading. Mapbox draws a model's +Z towards the south with no rotation, takes its offset as metres
 * [east, south, up] and turns it clockwise (seen from above) for positive rotation.
 *
 * Launch Library gives each flight's countdown and flight events (propellant load, ignition, stage
 * separation, landing burn...); the scene runs on those when listed, on recent flights' averages
 * otherwise. Stacking isn't published anywhere, so that part is a reenactment at typical times.
 */

/**
 * Starbase's orbital pads: each tower's position, the compass bearing from it to its mount, and the
 * bearing to watch from: side-on to tower and mount (so stacking shows), with the eastbound flight
 * heading off to the right.
 */
enum class Pad(val launchLibraryId: Int, val tower: Point, val heading: Double, val viewBearing: Double) {
    // Positions from OpenStreetMap ("Starbase Integration Tower 1/2"); headings from each tower
    // to its launch mount there.
    PAD_1(188, Point.fromLngLat(-97.154736, 25.996126), 87.0, 350.0),
    PAD_2(235, Point.fromLngLat(-97.158067, 25.997046), 183.0, 300.0),
}

/** A model MinMap draws at Starbase. Flames, vapour and steam are MinMap's own effect models. */
enum class Part(val asset: String) {
    TOWER("tower"), CARRIAGE("carriage"), ARM_LEFT("arm-left"), ARM_RIGHT("arm-right"), QD_ARM("qd-arm"),
    MOUNT("mount"), BOOSTER("booster"), SHIP("ship"),
    BOOSTER_FLAME("flame"), SHIP_FLAME("flame"), BOOSTER_VENT("vapor"), SHIP_VENT("vapor"), STEAM("steam"),
}

/**
 * Where and how to draw one part: Mapbox's model translation (metres east, south, up), its heading
 * (degrees clockwise), a tilt of [tilt] degrees leaning towards compass bearing [tiltToward], a
 * uniform [scale], [opacity], and [frost] (0-1, how white the steel has frosted over).
 */
data class Pose(
    val east: Double,
    val south: Double,
    val up: Double,
    val heading: Double,
    val tilt: Double = 0.0,
    val tiltToward: Double = 0.0,
    val scale: Double = 1.0,
    val opacity: Double = 1.0,
    val frost: Double = 0.0,
)

data class PlacedPart(val pad: Pad, val part: Part, val pose: Pose?) {
    val key get() = "minmap-starbase-${pad.name.lowercase()}-${part.name.lowercase()}"
}

/** A Starship flight from Starbase, as Launch Library 2 lists it. */
data class StarshipLaunch(
    val name: String,
    /** Liftoff time (or the planned one), epoch milliseconds. */
    val net: Long,
    val pad: Pad,
    /** Launch Library's status abbreviation: Go, TBC, TBD, Hold, In Flight, Success, Failure... */
    val status: String,
    /** Whether Super Heavy comes back to be caught by the tower (otherwise it splashes down). */
    val boosterCatch: Boolean,
    /** Countdown and flight events by Launch Library's name for them, in seconds from liftoff. */
    val timeline: Map<String, Double> = emptyMap(),
    /** How far out the booster splashes down, when listed. */
    val splashdownKm: Double? = null,
)

/** Where every part is [now] (epoch ms). Parts with a null pose aren't drawn. */
fun starbaseScene(launch: StarshipLaunch?, now: Long): List<PlacedPart> =
    Pad.entries.flatMap { pad -> padScene(pad, launch?.takeIf { it.pad == pad }, now) }

/** Whether the scene is changing from moment to moment (so it needs redrawing every frame). */
fun starbaseMoving(launch: StarshipLaunch?, now: Long): Boolean {
    if (launch == null || !planned(launch)) return false
    val t = (now - launch.net) / 1000.0
    val e = Events(launch)
    return (t in e.boosterLift..e.boosterLift + STACK_S) || (t in e.shipStack..e.shipStack + STACK_S) ||
        (t in e.propLoad..STEAM_GONE_S && launched(launch)) || (launched(launch) && t in 0.0..SHIP_GONE_S)
}

/** Seconds from liftoff to each step: the flight's own timeline where listed, else typical times. */
private class Events(launch: StarshipLaunch) {
    private val tl = launch.timeline
    val boosterLift = -BOOSTER_LIFT_BEFORE_S
    val shipStack = -SHIP_STACK_BEFORE_S
    val propLoad = tl["Stage 1 LOX Load"] ?: tl["GO for Prop Load"]?.plus(14 * 60) ?: -36.5 * 60
    val chill = tl["Engine Chill"] ?: -21.7 * 60
    val deluge = tl["Flame Deflector Activation"] ?: -17.0
    val ignition = tl["Ignition"] ?: -3.0
    val separation = tl["Stage 2 Separation"] ?: tl["MECO"]?.plus(2) ?: STAGING_S
    val boostbackStart = tl["Booster Boostback Burn Startup"] ?: (separation + 5)
    val boostbackEnd = tl["Booster Boostback Burn Shutdown"] ?: (separation + 45)
    val landingBurn = tl["Stage 1 Landing Burn"] ?: LANDING_BURN_S
    val landing = tl["Stage 1 Landing"] ?: CATCH_S

    /** Maps this flight's time onto the reference profile below, so staging and landing line up. */
    fun warp(t: Double): Double = when {
        t <= 0 -> t
        t <= separation -> t * STAGING_S / separation
        t <= landingBurn -> STAGING_S + (t - separation) * (LANDING_BURN_S - STAGING_S) / (landingBurn - separation)
        t <= landing -> LANDING_BURN_S + (t - landingBurn) * (CATCH_S - LANDING_BURN_S) / (landing - landingBurn)
        else -> CATCH_S + (t - landing)
    }
}

private fun launched(launch: StarshipLaunch) = launch.status in LAUNCHING
private fun planned(launch: StarshipLaunch) = launch.status != "TBD"

/** A vehicle's state: where its base is (in the pad's frame, or downrange of the mount) and its tilt. */
private class Vehicle(val x: Double = 0.0, val y: Double, val z: Double, val downrange: Double = 0.0, val tilt: Double = 0.0)

private fun padScene(pad: Pad, launch: StarshipLaunch?, now: Long): List<PlacedPart> {
    val t = launch?.let { (now - it.net) / 1000.0 } ?: Double.NEGATIVE_INFINITY
    val e = launch?.let(::Events)

    var carriage = 0.0      // metres the chopsticks carriage (and arms) sit above or below their parked height
    var armsClosed = 0.0    // 0 parked open, 1 closed round a vehicle
    var qdOpen = 1.0        // 0 on the ship, 1 swung clear
    var booster: Vehicle? = null
    var ship: Vehicle? = null
    var boosterFlame = 0.0
    var shipFlame = 0.0
    var boosterVent = 0.0
    var shipVent = 0.0
    var steam = 0.0
    var steamSpread = 1.0
    var frost = 0.0
    var heading = 0.0       // extra heading for the stack: the roll to its flight heading after liftoff

    if (launch != null && e != null && planned(launch)) {
        val flying = launched(launch) && t >= 0
        val onPad = !flying && t >= e.boosterLift - ROLLOUT_LEAD_S &&
            t < (if (launched(launch)) 0.0 else SCRUB_HOLD_S)
        if (onPad) {
            // Stacking: each vehicle waits on its stand beside the mount, then the chopsticks come
            // down, close on it, lift it, carry it over the mount and set it down.
            val b = stack(t - e.boosterLift, grip = BOOSTER_GRIP, liftTo = BOOSTER_BASE + CATCH_HEIGHT, setDownAt = BOOSTER_BASE)
            booster = Vehicle(y = b.base, z = b.z)
            carriage = b.carriage
            armsClosed = b.armsClosed
            if (t >= e.shipStack - ROLLOUT_LEAD_S) {
                val s = stack(t - e.shipStack, grip = SHIP_GRIP, liftTo = SHIP_BASE + 2.0, setDownAt = SHIP_BASE)
                ship = Vehicle(y = s.base, z = s.z)
                if (t >= e.shipStack) {
                    carriage = s.carriage
                    armsClosed = s.armsClosed
                }
                // The ship's quick-disconnect arm swings in once the ship is on.
                qdOpen = 1 - ramp(t - e.shipStack, STACK_S - 150, STACK_S - 30)
            }
            if (launched(launch)) {
                // Fuelling: the steel frosts over as the cryogenic propellant goes in, and both stages vent.
                frost = FROST_MAX * ramp(t, e.propLoad, e.propLoad + 8 * 60)
                val venting = t in e.propLoad..e.ignition
                boosterVent = if (venting) (if (t >= e.chill) 1.4 else 0.9) * puff(t, 3.1) else 0.0
                shipVent = if (venting) 0.8 * puff(t + 1.3, 4.3) else 0.0
                qdOpen = maxOf(qdOpen, ramp(t, -QD_RETRACT_S, -1.0))
                steam = ramp(t, e.deluge, e.deluge + 6)
                steamSpread = 0.4 + 0.6 * steam
                boosterFlame = ramp(t, e.ignition, 0.0) * 0.6 * flicker(t)
            }
        } else if (flying) {
            val w = e.warp(t)
            frost = FROST_MAX * (1 - ramp(w, 60.0, STAGING_S))
            // Deluge steam billows out after liftoff, then thins and drifts off.
            steam = 1 - ramp(t, 40.0, STEAM_GONE_S)
            steamSpread = 1 + 0.5 * ramp(t, 0.0, STEAM_GONE_S)
            // Roll program: turn from the pad's heading to the flight's within the first seconds.
            heading = ramp(t, 4.0, 14.0)
            if (w < STAGING_S) {
                val climb = ascent(w)
                val tilt = curve1(w, STACK_TILT_T, STACK_TILT)
                booster = Vehicle(y = BOOSTER_BASE + climb[1], z = ROCKET_Z, downrange = climb[0], tilt = tilt)
                ship = stackedShip(booster)
                boosterFlame = (1 + ramp(w, 0.0, 90.0)) * flicker(t)
            } else {
                if (w < SHIP_GONE_S) {
                    val s = shipFlight(w)
                    ship = Vehicle(y = SHIP_BASE + s[1], z = ROCKET_Z, downrange = s[0], tilt = curve1(w, SHIP_TILT_T, SHIP_TILT))
                    shipFlame = 0.6 * flicker(t + 0.5)
                }
                val bTilt = curve1(w, BOOSTER_TILT_T, BOOSTER_TILT)
                if (launch.boosterCatch) {
                    if (t < e.landing + HOLD_AFTER_CATCH_S) {
                        val r = boosterReturn(w)
                        booster = Vehicle(y = BOOSTER_BASE + r[1], z = ROCKET_Z, downrange = r[0], tilt = bTilt)
                    }
                    armsClosed = if (t < e.landing + HOLD_AFTER_CATCH_S) ramp(w, CATCH_S - 9, CATCH_S - 1) else 0.0
                } else if (w < SPLASHDOWN_S) {
                    val r = boosterToSea(w, launch.splashdownKm)
                    booster = Vehicle(y = BOOSTER_BASE + r[1], z = ROCKET_Z, downrange = r[0], tilt = bTilt)
                }
                if (booster != null && booster.y > BOOSTER_BASE + CATCH_HEIGHT + 1) {
                    val burning = t in e.boostbackStart..e.boostbackEnd || t in e.landingBurn..e.landing
                    if (burning) boosterFlame = (if (t >= e.landingBurn) 0.45 else 0.7) * flicker(t)
                }
            }
        }
    }

    val padYaw = pad.heading - 180
    fun place(x: Double, y: Double, z: Double, turn: Double = 0.0, downrange: Double = 0.0) = Pose(
        east = x * cos(rad(padYaw)) - z * sin(rad(padYaw)) + downrange * sin(rad(FLIGHT_BEARING)),
        south = x * sin(rad(padYaw)) + z * cos(rad(padYaw)) - downrange * cos(rad(FLIGHT_BEARING)),
        up = y,
        heading = padYaw + turn,
    )
    // In flight the stack rolls from the pad's heading to north-up, so its tilt can lean straight
    // towards the flight bearing whichever pad it left.
    fun vehicle(v: Vehicle?, scale: Double = 1.0, frost: Double = 0.0): Pose? = v?.let {
        place(it.x, it.y, it.z, downrange = it.downrange).copy(
            heading = padYaw * (1 - heading),
            tilt = it.tilt,
            tiltToward = FLIGHT_BEARING,
            scale = scale,
            frost = frost,
        )
    }
    fun effect(v: Vehicle?, amount: Double, scale: Double, dy: Double = 0.0, dx: Double = 0.0): Pose? {
        if (v == null || amount <= 0.01) return null
        return vehicle(Vehicle(v.x + dx, v.y + dy, v.z, v.downrange, v.tilt), scale = scale * amount)
    }

    return listOf(
        PlacedPart(pad, Part.TOWER, place(0.0, 0.0, 0.0)),
        PlacedPart(pad, Part.CARRIAGE, place(0.0, carriage, 0.0)),
        // Closing turns the left arm clockwise and the right one anticlockwise (seen from above).
        PlacedPart(pad, Part.ARM_LEFT, place(6.4, 90.4 + carriage, 6.4, turn = ARM_CLOSE_DEG * armsClosed)),
        PlacedPart(pad, Part.ARM_RIGHT, place(-6.4, 90.4 + carriage, 6.4, turn = -ARM_CLOSE_DEG * armsClosed)),
        PlacedPart(pad, Part.QD_ARM, place(5.2, 97.5, 5.2, turn = -QD_SWING_DEG * qdOpen)),
        PlacedPart(pad, Part.MOUNT, place(0.0, 0.0, ROCKET_Z)),
        PlacedPart(pad, Part.BOOSTER, vehicle(booster, frost = frost)),
        PlacedPart(pad, Part.SHIP, vehicle(ship, frost = frost)),
        PlacedPart(pad, Part.BOOSTER_FLAME, effect(booster, boosterFlame, 1.0)),
        PlacedPart(pad, Part.SHIP_FLAME, effect(ship, shipFlame, 0.6)),
        // Venting: round the booster's base below the mount ring, and at the ship's base by the QD arm.
        PlacedPart(pad, Part.BOOSTER_VENT, effect(booster, boosterVent, 1.6, dy = -4.0)),
        PlacedPart(pad, Part.SHIP_VENT, effect(ship, shipVent, 1.2, dy = 4.0, dx = 4.5)),
        PlacedPart(pad, Part.STEAM, if (steam > 0.01) place(0.0, 0.0, ROCKET_Z).copy(scale = steamSpread, opacity = 0.9 * steam) else null),
    )
}

/** While stacked, the ship stands on the booster's top, leaning with it. */
private fun stackedShip(b: Vehicle): Vehicle {
    val lean = rad(b.tilt)
    return Vehicle(
        y = b.y + BOOSTER_LENGTH * cos(lean),
        z = b.z,
        downrange = b.downrange + BOOSTER_LENGTH * sin(lean),
        tilt = b.tilt,
    )
}

private class Stacking(val base: Double, val z: Double, val carriage: Double, val armsClosed: Double)

/**
 * One chopsticks lift, [s] seconds in: the arms (gripping [grip] metres above the vehicle's base)
 * come down to the vehicle on its stand beside the mount, close, lift it to [liftTo], carry it over
 * the mount, lower it to [setDownAt], let go and go back up.
 */
private fun stack(s: Double, grip: Double, liftTo: Double, setDownAt: Double): Stacking {
    // Carriage offset that puts the arms' catch rails at the vehicle's grip points.
    fun carriageFor(base: Double) = base + grip - ARM_RAIL_TOP
    val m = s / 60
    val down = carriageFor(0.0)
    val lifted = carriageFor(liftTo)
    val set = carriageFor(setDownAt)
    return when {
        m < 0 -> Stacking(0.0, STAND_Z, 0.0, 0.0)
        m < 3 -> Stacking(0.0, STAND_Z, down * ramp(m, 0.0, 3.0), 0.0)
        m < 4 -> Stacking(0.0, STAND_Z, down, ramp(m, 3.0, 4.0))
        m < 10 -> ramp(m, 4.0, 10.0).let { f -> Stacking(liftTo * f, STAND_Z, down + (lifted - down) * f, 1.0) }
        m < 13 -> Stacking(liftTo, STAND_Z + (ROCKET_Z - STAND_Z) * ramp(m, 10.0, 13.0), lifted, 1.0)
        m < 16 -> ramp(m, 13.0, 16.0).let { f -> Stacking(liftTo + (setDownAt - liftTo) * f, ROCKET_Z, lifted + (set - lifted) * f, 1.0) }
        m < 17 -> Stacking(setDownAt, ROCKET_Z, set, 1 - ramp(m, 16.0, 17.0))
        else -> Stacking(setDownAt, ROCKET_Z, set * (1 - ramp(m, 17.0, 20.0)), 0.0)
    }
}

// --- Flight profile: rough averages of recent Starship flights (seconds after liftoff, metres). ---

/** Both stages together, liftoff to hot staging: [downrange, height]. */
private fun ascent(t: Double) = curve(
    t,
    doubleArrayOf(0.0, 4.0, 8.0, 12.0, 16.0, 20.0, 30.0, 40.0, 60.0, 90.0, 120.0, STAGING_S),
    doubleArrayOf(0.0, 0.0, 0.0, 0.0, 2.0, 15.0, 80.0, 250.0, 1_400.0, 6_000.0, 17_000.0, 52_000.0),
    doubleArrayOf(0.0, 8.0, 30.0, 70.0, 135.0, 230.0, 600.0, 1_300.0, 4_300.0, 13_500.0, 30_000.0, 64_000.0),
)

private fun shipFlight(t: Double) = curve(
    t,
    doubleArrayOf(STAGING_S, 240.0, 400.0, SHIP_GONE_S),
    doubleArrayOf(52_000.0, 120_000.0, 300_000.0, 600_000.0),
    doubleArrayOf(64_000.0, 100_000.0, 140_000.0, 160_000.0),
)

/** Boostback, coast and landing burn, ending held in the chopsticks above the mount. */
private fun boosterReturn(t: Double) = curve(
    t,
    doubleArrayOf(STAGING_S, 230.0, 300.0, 350.0, 385.0, LANDING_BURN_S, 400.0, 405.0, 410.0, 414.0, CATCH_S),
    doubleArrayOf(52_000.0, 45_000.0, 30_000.0, 12_000.0, 2_500.0, 1_200.0, 400.0, 120.0, 30.0, 5.0, 0.0),
    doubleArrayOf(64_000.0, 92_000.0, 70_000.0, 30_000.0, 8_000.0, 4_000.0, 2_500.0, 1_200.0, 400.0, 80.0, CATCH_HEIGHT),
)

/** Boostback towards the coast and a splashdown out in the Gulf ([km] out when listed, else 30 km). */
private fun boosterToSea(t: Double, km: Double?): DoubleArray {
    val out = (km ?: 30.0) * 1000
    return curve(
        t,
        doubleArrayOf(STAGING_S, 230.0, 300.0, 360.0, 400.0, SPLASHDOWN_S),
        doubleArrayOf(52_000.0, maxOf(out, 48_000.0), maxOf(out, 40_000.0), out + 3_000, out + 500, out),
        doubleArrayOf(64_000.0, 90_000.0, 60_000.0, 20_000.0, 3_000.0, 0.0),
    )
}

// Tilt from upright, degrees, leaning towards the flight bearing (negative: back towards the pad).
private val STACK_TILT_T = doubleArrayOf(0.0, 15.0, 30.0, 60.0, 90.0, 120.0, STAGING_S)
private val STACK_TILT = doubleArrayOf(0.0, 0.0, 3.0, 15.0, 30.0, 45.0, 57.0)
private val SHIP_TILT_T = doubleArrayOf(STAGING_S, 300.0, SHIP_GONE_S)
private val SHIP_TILT = doubleArrayOf(57.0, 75.0, 85.0)
/** The booster flips to fly back for its boostback, then comes down upright. */
private val BOOSTER_TILT_T = doubleArrayOf(STAGING_S, 200.0, 330.0, 365.0)
private val BOOSTER_TILT = doubleArrayOf(57.0, -25.0, -10.0, 0.0)

/** Linear interpolation of [downrange] and [height] at [t] along [times]. */
private fun curve(t: Double, times: DoubleArray, downrange: DoubleArray, height: DoubleArray) =
    doubleArrayOf(curve1(t, times, downrange), curve1(t, times, height))

private fun curve1(t: Double, times: DoubleArray, values: DoubleArray): Double {
    val i = times.indexOfLast { it <= t }.coerceIn(0, times.size - 2)
    val f = ((t - times[i]) / (times[i + 1] - times[i])).coerceIn(0.0, 1.0)
    return values[i] + (values[i + 1] - values[i]) * f
}

/** 0 before [from], 1 after [to], eased in between. */
private fun ramp(t: Double, from: Double, to: Double): Double {
    val x = ((t - from) / (to - from)).coerceIn(0.0, 1.0)
    return x * x * (3 - 2 * x)
}

/** A vent's size over time: puffs every [period] seconds, never quite gone. */
private fun puff(t: Double, period: Double) = 0.75 + 0.35 * sin(2 * PI * t / period)

/** Engine plumes shimmer a little. */
private fun flicker(t: Double) = 1 + 0.05 * sin(t * 37) + 0.03 * sin(t * 91)

private fun rad(deg: Double) = deg * PI / 180

/** Statuses for a launch that's going ahead or has gone. */
private val LAUNCHING = setOf("Go", "In Flight", "Success", "Failure", "Partial Failure")

/** Stacking (not published anywhere) at typical times: booster a day and a quarter out, ship a day. */
private const val BOOSTER_LIFT_BEFORE_S = 30 * 3600.0
private const val SHIP_STACK_BEFORE_S = 26 * 3600.0
/** Each vehicle stands by the mount for this long before its lift. */
private const val ROLLOUT_LEAD_S = 2 * 3600.0
private const val STACK_S = 20 * 60.0
/** After a hold or scrub past the planned time, it stays on the pad this long (until a new date). */
private const val SCRUB_HOLD_S = 2 * 3600.0
private const val QD_RETRACT_S = 4.0
private const val STAGING_S = 162.0
private const val LANDING_BURN_S = 395.0
private const val CATCH_S = 417.0
private const val SPLASHDOWN_S = 420.0
private const val SHIP_GONE_S = 600.0
private const val STEAM_GONE_S = 150.0
/** A caught booster hangs in the chopsticks for a few hours before it's set down and rolled away. */
private const val HOLD_AFTER_CATCH_S = 3 * 3600.0
/** Starship flights from Starbase head east-southeast over the Gulf. */
private const val FLIGHT_BEARING = 95.0
private const val FROST_MAX = 0.45

// Model geometry (metres): the rocket stands 22 m out from the tower; the mount's ring holds the
// booster's base 20 m up and the ship sits on the 71 m booster. Caught, the booster's catch pins rest
// on the chopsticks with its base 8.5 m above the mount ring. Vehicles wait for stacking on stands
// 14 m further out, still between the closed arms (which reach 42 m out).
private const val ROCKET_Z = 22.0
private const val STAND_Z = 36.0
private const val BOOSTER_BASE = 20.0
private const val BOOSTER_LENGTH = 71.0
private const val SHIP_BASE = 91.0
private const val CATCH_HEIGHT = 8.5
/** Height of the arms' catch rails with the carriage parked. */
private const val ARM_RAIL_TOP = 90.95
/** Where the chopsticks take hold: the booster's catch pins, and the ship's lift points. */
private const val BOOSTER_GRIP = 62.05
private const val SHIP_GRIP = 40.0
/** Parked, the chopsticks stand open 14 degrees each side; closed they grip the vehicle. */
private const val ARM_CLOSE_DEG = 14.0
private const val QD_SWING_DEG = 70.0
