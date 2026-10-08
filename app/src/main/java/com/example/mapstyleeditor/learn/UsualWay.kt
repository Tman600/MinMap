package com.example.mapstyleeditor.learn

import com.example.mapstyleeditor.nav.Route
import com.example.mapstyleeditor.nav.distanceMeters
import com.mapbox.geojson.Point
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Your usual way to [destination] from around [here], as pass-through points where it leaves
 * [fastest]. Empty when there's no habit to follow: fewer than two recorded drives from near here,
 * drives that don't agree with each other, or a usual way that already is the fastest.
 *
 * The points go to Google Maps as stops, so it guides along your roads instead of its own pick.
 */
fun usualWayVias(trips: List<Trip>, here: Point, destination: Point, fastest: Route): List<Point> {
    val paths = trips
        .filter { distanceMeters(it.destination, destination) < SAME_PLACE_METERS }
        .mapNotNull { it.path?.takeIf { p -> p.size >= 5 } }
        .filter { distanceMeters(it.first(), here) < SAME_START_METERS }
        .map { Route(it, emptyList()) }
    if (paths.size < 2) return emptyList()

    // The most typical drive: the one closest, on average, to all the others.
    val usual = paths.minBy { a -> paths.sumOf { b -> if (a === b) 0.0 else gap(a, b) } }
    val agreement = paths.filter { it !== usual }.map { gap(usual, it) }.average()
    if (agreement > HABIT_SPREAD_METERS) return emptyList() // You don't really have one way.

    // Walk the usual way and mark where it runs well away from the fastest route.
    val samples = (1 until SAMPLES).map { usual.pointAt(usual.cumulative.last() * it / SAMPLES) }
    val off = samples.map { fastest.locate(it).second > DIVERGE_METERS }

    // Each stretch that differs gets one point in its middle, enough to pull Google onto it.
    val vias = mutableListOf<Point>()
    var start = -1
    for (i in 0..off.size) {
        val isOff = i < off.size && off[i]
        if (isOff && start < 0) start = i
        if (!isOff && start >= 0) {
            vias += samples[(start + i - 1) / 2]
            start = -1
        }
    }
    return vias.take(MAX_VIAS)
}

/** Average distance from points along [a] to the line [b], in meters. */
private fun gap(a: Route, b: Route): Double =
    (1 until 20).map { b.locate(a.pointAt(a.cumulative.last() * it / 20)).second }.average()

/**
 * Thins a recorded GPS trace to the points that matter (Douglas-Peucker, ~25 m tolerance), so
 * months of drives stay small in storage.
 */
fun simplifyPath(points: List<Point>, toleranceMeters: Double = 25.0): List<Point> {
    if (points.size < 3) return points
    val keep = BooleanArray(points.size).also { it[0] = true; it[it.size - 1] = true }
    fun visit(from: Int, to: Int) {
        var worst = -1
        var worstDist = toleranceMeters
        for (i in from + 1 until to) {
            val d = offLine(points[i], points[from], points[to])
            if (d > worstDist) {
                worst = i
                worstDist = d
            }
        }
        if (worst >= 0) {
            keep[worst] = true
            visit(from, worst)
            visit(worst, to)
        }
    }
    visit(0, points.size - 1)
    return points.filterIndexed { i, _ -> keep[i] }
}

/** Distance in meters from [p] to segment a-b, on a local flat approximation. */
private fun offLine(p: Point, a: Point, b: Point): Double {
    val kx = cos(Math.toRadians(a.latitude())) * 111_320.0
    val ky = 110_540.0
    val bx = (b.longitude() - a.longitude()) * kx
    val by = (b.latitude() - a.latitude()) * ky
    val px = (p.longitude() - a.longitude()) * kx
    val py = (p.latitude() - a.latitude()) * ky
    val len2 = bx * bx + by * by
    val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
    val dx = px - t * bx
    val dy = py - t * by
    return sqrt(dx * dx + dy * dy)
}

private const val SAME_PLACE_METERS = 150.0
/** Drives count toward a habit only if they started within this distance of where you are. */
private const val SAME_START_METERS = 2_000.0
/** How far apart your past drives may run on average and still be "one usual way". */
private const val HABIT_SPREAD_METERS = 150.0
/** How far off the fastest route a stretch must be to count as your own way. */
private const val DIVERGE_METERS = 200.0
private const val SAMPLES = 40
/** Google Maps takes a handful of stops from a link; a few are plenty to pin a route. */
private const val MAX_VIAS = 3
