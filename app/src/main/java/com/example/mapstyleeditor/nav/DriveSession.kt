package com.example.mapstyleeditor.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mapbox.geojson.Point
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Keeps our own route line in step with Google Maps while it navigates.
 *
 * Google only reveals its next turn (via its notification), so the line is a Mapbox route that
 * gets bent through each turn Google announces: if our next turns don't include Google's street,
 * find where that street meets our line and re-route through it. The stretch ahead of the driver
 * then always matches Google; further out it's Mapbox's best guess until the next turn arrives.
 */
class DriveSession(
    private val token: String,
    val destination: Point,
    /** When the drive started; also the key of its entry in the trip history. */
    val startedAt: Long,
    /** Points on your usual way that Google Maps was told to pass through (empty if none). */
    vias: List<Point>,
    private val location: () -> Point?,
    private val bearing: () -> Double?,
) {
    /** Usual-way points not reached yet; our line keeps going through these, like Google does. */
    private var remainingVias = vias
    var route by mutableStateOf<Route?>(null)
        private set

    /**
     * "Arrive 2:41 PM" for the whole trip while usual-way stops remain. Google Maps then only times
     * the leg to its next stop, so this adds it all up along our line instead. Null once the stops
     * are behind you (Google's own arrival covers the rest) or when it can't be worked out.
     */
    var tripArrival by mutableStateOf<String?>(null)
        private set

    private var handledInstruction: String? = null
    private var offRouteChecks = 0
    private var lastRouteAt = 0L

    suspend fun run() {
        while (true) {
            tick()
            delay(TICK_MS)
        }
    }

    private suspend fun tick() {
        val here = location() ?: return
        remainingVias = remainingVias.filter { distanceMeters(it, here) > VIA_REACHED_METERS }
        val current = route
        if (current == null) {
            // First route (or retrying after a failed request, without hammering the API).
            if (System.currentTimeMillis() - lastRouteAt > RETRY_MS) reroute(listOf(here) + remainingVias + destination)
            return
        }

        // Left our line (followed Google somewhere else): start over from here, and re-check
        // Google's current turn against the new line.
        val (along, off) = current.locate(here)
        tripArrival = if (remainingVias.isEmpty()) null else current.secondsLeft(along)?.let { seconds ->
            "Arrive " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + (seconds * 1000).toLong()))
        }
        offRouteChecks = if (off > OFF_ROUTE_METERS) offRouteChecks + 1 else 0
        if (offRouteChecks >= 2 && System.currentTimeMillis() - lastRouteAt > REROUTE_COOLDOWN_MS) {
            offRouteChecks = 0
            handledInstruction = null
            reroute(listOf(here) + remainingVias + destination)
            return
        }

        val nav = GoogleNav.current.value ?: return
        val street = nav.street ?: return
        val key = nav.instruction + "|" + street
        if (key == handledInstruction) return
        handledInstruction = key

        val upcoming = current.steps.filter { it.alongMeters > along - 15 }.take(3)
        if (upcoming.any { sameRoad(street, it.names) }) return // Already agrees with Google.

        // Where Google's turn should be: its distance, measured along our line from here.
        val distance = nav.distanceMeters ?: return
        val angle = turnAngle(nav.instruction) ?: return // U-turn: one point can't express it.
        val corner = current.pointAt(along + distance)
        // The way you'll be heading once you've turned: our approach direction, turned as Google says.
        val heading = (current.bearingAt(along + distance) + angle + 360) % 360
        // Aim a little way down the new street on the side you're turning to. A street runs both
        // ways from the corner; snapping right at the corner could pick the far side, and the route
        // would go there and loop back (the "both ways at the intersection" line).
        val ahead = offset(corner, heading, LOOK_AHEAD_METERS)
        val radius = (distance * 0.25).coerceIn(80.0, 300.0).toInt()
        val turn = snapToStreet(token, ahead, street, radius) ?: return
        reroute(
            listOf(here, turn) + remainingVias + destination,
            stopBearings = mapOf(1 to heading),
            replacingMeters = current.cumulative.last() - along,
        )
    }

    /**
     * Fetches a new line through [stops]. With [stopBearings], it must pass those stops heading that
     * way; if that's impossible (e.g. the street bends more than expected), it tries once without.
     *
     * [replacingMeters] is how much line is left on the route being corrected. Following Google's
     * next turn shouldn't make the trip much longer; if the new line does, the steering point landed
     * on the wrong side and the route detours to reach it (tested on local roads: 0.4-3.7 mi extra),
     * so the current line is kept instead.
     */
    private suspend fun reroute(
        stops: List<Point>,
        stopBearings: Map<Int, Double> = emptyMap(),
        replacingMeters: Double? = null,
    ) {
        lastRouteAt = System.currentTimeMillis()
        val next = fetchRoute(token, stops, bearing(), stopBearings)
            ?: (if (stopBearings.isNotEmpty()) fetchRoute(token, stops, bearing()) else null)
            ?: return
        if (replacingMeters != null) {
            val extra = next.cumulative.last() - replacingMeters
            if (extra > maxOf(DETOUR_MIN_METERS, replacingMeters * DETOUR_FRACTION)) return
        }
        route = next
    }

    private companion object {
        const val TICK_MS = 2_000L
        const val OFF_ROUTE_METERS = 60.0
        const val REROUTE_COOLDOWN_MS = 10_000L
        const val RETRY_MS = 5_000L
        const val VIA_REACHED_METERS = 150.0
        /** How far past the corner, along the street being turned onto, the steering point goes. */
        const val LOOK_AHEAD_METERS = 60.0
        /** A correction adding more than this (and more than [DETOUR_FRACTION]) is treated as a detour. */
        const val DETOUR_MIN_METERS = 400.0
        const val DETOUR_FRACTION = 0.08
    }
}
