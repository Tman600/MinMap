package com.example.mapstyleeditor.nav

import com.example.mapstyleeditor.learn.Trip
import com.example.mapstyleeditor.learn.usualWayVias
import com.mapbox.geojson.Point
import kotlinx.coroutines.withTimeoutOrNull

/** How a drive MinMap guides by itself begins: your usual way's stops (if any) and a first route. */
class OwnDrivePlan(val vias: List<Point>, val route: Route?)

/**
 * Plans a drive MinMap guides by itself (the aosp build, and Android Auto). One Directions request:
 * the fastest route, with its spoken instructions, which also decides whether you have a usual way
 * there. That route is the drive's first route unless you do; then the drive fetches one through
 * your usual roads instead.
 */
suspend fun planOwnDrive(token: String, trips: List<Trip>, here: Point?, bearing: Double?, destination: Point): OwnDrivePlan {
    val fastest = if (here == null) null else withTimeoutOrNull(10_000) {
        fetchRoute(token, listOf(here, destination), bearing, guidance = true)
    }
    val vias = if (here == null || fastest == null) emptyList() else usualWayVias(trips, here, destination, fastest)
    return OwnDrivePlan(vias, fastest.takeIf { vias.isEmpty() })
}
