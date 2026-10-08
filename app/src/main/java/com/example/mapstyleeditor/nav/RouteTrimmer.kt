package com.example.mapstyleeditor.nav

import com.mapbox.bindgen.Value
import com.mapbox.geojson.Point
import com.mapbox.maps.MapboxMap
import kotlin.math.abs

/** Style layer IDs of the drive route line (outline and fill), so they can be trimmed directly. */
val ROUTE_LAYER_IDS = listOf("minmap-route-casing", "minmap-route")

/**
 * Erases the route line behind the puck as it moves. Driven by the puck's own position updates (one
 * per animation frame), so the line's end stays glued to the puck instead of lagging a step behind.
 *
 * It sets the layers' `line-trim-offset` straight on the map rather than through Compose state, so
 * following the puck doesn't recompose the screen 60 times a second.
 */
class RouteTrimmer {
    /** The line currently drawn; set from Compose whenever the drive's route changes. */
    @Volatile var route: Route? = null
        set(value) {
            field = value
            segment = 0
            shownFraction = -1.0
            refresh()
        }

    var map: MapboxMap? = null
    private var puck: Point? = null
    private var segment = 0
    private var shownFraction = -1.0

    fun onPuckMoved(point: Point) {
        puck = point
        refresh()
    }

    private fun refresh() {
        val map = map ?: return
        val route = route ?: return
        val here = puck ?: return
        val length = route.cumulative.last().takeIf { it > 0 } ?: return
        val (along, index) = route.locateNear(here, segment)
        segment = index
        val fraction = (along / length).coerceIn(0.0, 1.0)
        // Skip imperceptible changes (well under a metre on a normal route).
        if (abs(fraction - shownFraction) < MIN_STEP) return
        shownFraction = fraction
        val trim = Value(arrayListOf(Value(0.0), Value(fraction)))
        ROUTE_LAYER_IDS.forEach { map.setStyleLayerProperty(it, "line-trim-offset", trim) }
    }

    private companion object {
        const val MIN_STEP = 0.0001
    }
}
