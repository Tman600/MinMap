package com.example.mapstyleeditor.nav

import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting
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
 *
 * With [ownGuidance] (the aosp build, no Google Maps) there's no one to follow: the session guides
 * by itself from its route's steps, publishing [guidance] for the turn card and speaking each cue.
 * That needs one Directions request per route and nothing per turn; leaving the route re-routes,
 * less and less often if it keeps happening.
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
    private val ownGuidance: Boolean = false,
    /** A route already fetched for this trip (with guidance), so the drive doesn't fetch it again. */
    initialRoute: Route? = null,
    private val speak: (String) -> Unit = {},
    /** Draws the turn card's arrow (swapped out in tests, which have no Android graphics). */
    private val arrowFor: (type: String, modifier: String?) -> Bitmap? = ::maneuverArrow,
) {
    /** Usual-way points not reached yet; our line keeps going through these, like Google does. */
    private var remainingVias = vias
    var route by mutableStateOf(initialRoute)
        private set

    /** MinMap's own next turn ([ownGuidance] only); null until there's a route. */
    var guidance by mutableStateOf<NavInstruction?>(null)
        private set

    /** Reached the destination ([ownGuidance] only). */
    var arrived by mutableStateOf(false)
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
    private var lastRouteAt = if (initialRoute != null) System.currentTimeMillis() else 0L
    /** Waits between requests grow while they fail or keep being needed, and shrink back after. */
    private var retryMs = RETRY_MS
    private var rerouteCooldownMs = REROUTE_COOLDOWN_MS
    private var onRouteSince = 0L
    /** The route the voice cues below belong to, and how far along it they've been dealt with. */
    private var spokenRoute: Route? = null
    private var spokenUpTo = -1.0
    private val arrows = HashMap<String, Bitmap?>()

    suspend fun run() {
        while (!arrived) {
            tick()
            delay(if (ownGuidance) GUIDE_TICK_MS else TICK_MS)
        }
    }

    /** One look at where you are: guidance, cues, and rerouting. [run] calls it on a timer. */
    @VisibleForTesting
    internal suspend fun tick() {
        val here = location() ?: return
        remainingVias = remainingVias.filter { distanceMeters(it, here) > VIA_REACHED_METERS }
        val current = route
        if (current == null) {
            // First route (or retrying after a failed request, without hammering the API).
            if (System.currentTimeMillis() - lastRouteAt > retryMs) {
                reroute(listOf(here) + remainingVias + destination)
                retryMs = if (route == null) (retryMs * 2).coerceAtMost(MAX_WAIT_MS) else RETRY_MS
            }
            return
        }

        // Left our line (followed Google somewhere else): start over from here, and re-check
        // Google's current turn against the new line.
        val (along, off) = current.locate(here)
        tripArrival = if (remainingVias.isEmpty()) null else current.secondsLeft(along)?.let { seconds ->
            "Arrive " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + (seconds * 1000).toLong()))
        }
        if (ownGuidance) guide(current, along)
        if (arrived) return
        val now = System.currentTimeMillis()
        offRouteChecks = if (off > OFF_ROUTE_METERS) offRouteChecks + 1 else 0
        // Back on the route for a while: the next reroute may come quickly again.
        if (offRouteChecks > 0) onRouteSince = 0L else if (onRouteSince == 0L) onRouteSince = now
        if (onRouteSince > 0 && now - onRouteSince > CALM_MS) rerouteCooldownMs = REROUTE_COOLDOWN_MS
        // Our own guidance checks every second, so it waits for a few seconds off the line (GPS
        // jumps near tall buildings). A side trip back onto the line ahead never gets this far:
        // locate() finds the nearest part of the whole route.
        val needed = if (ownGuidance) 5 else 2
        if (offRouteChecks >= needed && now - lastRouteAt > rerouteCooldownMs) {
            offRouteChecks = 0
            handledInstruction = null
            reroute(listOf(here) + remainingVias + destination)
            if (ownGuidance) rerouteCooldownMs = (rerouteCooldownMs * 2).coerceAtMost(MAX_WAIT_MS)
            return
        }
        if (ownGuidance) return

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
        val next = fetchRoute(token, stops, bearing(), stopBearings, guidance = ownGuidance)
            ?: (if (stopBearings.isNotEmpty()) fetchRoute(token, stops, bearing(), guidance = ownGuidance) else null)
            ?: return
        if (replacingMeters != null) {
            val extra = next.cumulative.last() - replacingMeters
            if (extra > maxOf(DETOUR_MIN_METERS, replacingMeters * DETOUR_FRACTION)) return
        }
        route = next
    }

    /** Our own next turn, its spoken cues, and arriving. */
    private fun guide(current: Route, along: Double) {
        val total = current.cumulative.last()
        if (remainingVias.isEmpty() && total - along < ARRIVED_METERS) {
            // "You have arrived", unless it was already said on the way in.
            current.steps.flatMap { it.voice }.lastOrNull()?.takeIf { it.alongMeters >= spokenUpTo }?.let { speak(it.text) }
            arrived = true
            return
        }

        // Each cue once, as you pass its spot. One passed long ago (GPS gap, a fresh route that
        // starts mid-step) is skipped rather than read out late.
        if (current !== spokenRoute) {
            spokenRoute = current
            spokenUpTo = -1.0
        }
        val due = current.steps.flatMap { it.voice }.filter { it.alongMeters in spokenUpTo..along }
        due.lastOrNull()?.let { cue ->
            spokenUpTo = cue.alongMeters + 0.01
            if (along - cue.alongMeters < STALE_CUE_METERS) speak(cue.text)
        }

        val next = current.steps.firstOrNull { it.alongMeters > along + 5 && it.type != "depart" } ?: return
        val toTurn = next.alongMeters - along
        guidance = NavInstruction(
            instruction = next.instruction.ifBlank { next.names.firstOrNull().orEmpty() },
            distanceText = formatDistance(toTurn),
            distanceMeters = toTurn,
            street = next.names.firstOrNull(),
            arrival = current.secondsLeft(along)?.let { seconds ->
                "Arrive " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + (seconds * 1000).toLong()))
            },
            arrow = arrows.getOrPut(next.type + "|" + next.modifier) { arrowFor(next.type, next.modifier) },
            exitAction = null,
        )
    }

    private companion object {
        const val TICK_MS = 2_000L
        const val GUIDE_TICK_MS = 1_000L
        const val ARRIVED_METERS = 30.0
        const val STALE_CUE_METERS = 200.0
        /** Longest wait between route requests when they keep failing or keep being needed. */
        const val MAX_WAIT_MS = 180_000L
        /** On the route this long and reroutes are allowed at the normal pace again. */
        const val CALM_MS = 60_000L
        const val OFF_ROUTE_METERS = 60.0
        // Each reroute is a Directions request on the user's Mapbox account.
        const val REROUTE_COOLDOWN_MS = 20_000L
        const val RETRY_MS = 10_000L
        const val VIA_REACHED_METERS = 150.0
        /** How far past the corner, along the street being turned onto, the steering point goes. */
        const val LOOK_AHEAD_METERS = 60.0
        /** A correction adding more than this (and more than [DETOUR_FRACTION]) is treated as a detour. */
        const val DETOUR_MIN_METERS = 400.0
        const val DETOUR_FRACTION = 0.08
    }
}
