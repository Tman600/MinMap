package com.example.mapstyleeditor.nav

import android.animation.ValueAnimator
import com.mapbox.common.location.LocationError
import com.mapbox.geojson.Point
import com.mapbox.maps.plugin.locationcomponent.LocationConsumer
import com.mapbox.maps.plugin.locationcomponent.LocationProvider
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Puts the puck on the route while driving. Raw GPS sits a few metres to one side of the road's
 * centre line, which at drive zoom (especially the close-up view) reads as driving beside the
 * route. Within [SNAP_METERS] of the line, each position is moved onto it and the heading follows
 * the road; further off (you've actually left the route) the real position passes through.
 *
 * Wraps the normal GPS provider ([source]) and relays everything else unchanged.
 */
class RouteSnappingProvider(
    private val source: LocationProvider,
    private val route: () -> Route?,
) : LocationProvider {
    private val consumers = CopyOnWriteArraySet<LocationConsumer>()
    private var segment = 0
    /** Road heading at the last snapped position; null while unsnapped. */
    @Volatile private var roadBearing: Double? = null

    private var lastRoute: Route? = null

    private fun snap(p: Point): Point {
        val line = route() ?: return p.also { roadBearing = null }
        if (line !== lastRoute) {
            // New line (a correction or reroute): forget where we were on the old one.
            lastRoute = line
            segment = 0
        }
        val (along, index) = line.locateNear(p, segment)
        segment = index
        val onLine = line.pointAt(along)
        if (distanceMeters(p, onLine) > SNAP_METERS) {
            roadBearing = null
            return p
        }
        roadBearing = line.bearingAt(along + 1)
        return onLine
    }

    /** Last raw position the heading was worked out from, when off the line. */
    private var lastRaw: Point? = null

    private val relay = object : LocationConsumer {
        override fun onLocationUpdated(vararg location: Point, options: (ValueAnimator.() -> Unit)?) {
            val snapped = location.map(::snap).toTypedArray()
            consumers.forEach { it.onLocationUpdated(*snapped, options = options) }
            // The heading is worked out here rather than taken from the GPS provider: Mapbox only
            // switches that provider's course updates on when it's the puck's direct provider, which
            // it isn't once wrapped, so the puck (and the camera following it) stopped turning.
            heading(location.last())?.let { h -> consumers.forEach { it.onBearingUpdated(h) } }
        }

        override fun onBearingUpdated(vararg bearing: Double, options: (ValueAnimator.() -> Unit)?) {
            // Superseded by heading(): on the line the road's direction, off it the direction moved.
        }

        override fun onPuckLocationAnimatorDefaultOptionsUpdated(options: ValueAnimator.() -> Unit) =
            consumers.forEach { it.onPuckLocationAnimatorDefaultOptionsUpdated(options) }

        override fun onPuckBearingAnimatorDefaultOptionsUpdated(options: ValueAnimator.() -> Unit) =
            consumers.forEach { it.onPuckBearingAnimatorDefaultOptionsUpdated(options) }

        override fun onHorizontalAccuracyRadiusUpdated(vararg radius: Double, options: (ValueAnimator.() -> Unit)?) =
            consumers.forEach { it.onHorizontalAccuracyRadiusUpdated(*radius, options = options) }

        override fun onPuckAccuracyRadiusAnimatorDefaultOptionsUpdated(options: ValueAnimator.() -> Unit) =
            consumers.forEach { it.onPuckAccuracyRadiusAnimatorDefaultOptionsUpdated(options) }

        override fun onError(error: LocationError) = consumers.forEach { it.onError(error) }
    }

    /**
     * Direction for the puck: along the road while snapped to the line; otherwise the direction
     * between the last two positions, once you've moved far enough for it to mean something.
     * Null keeps the current heading (e.g. standing still off the route).
     */
    private fun heading(raw: Point): Double? {
        roadBearing?.let {
            lastRaw = raw
            return it
        }
        val from = lastRaw
        if (from == null) {
            lastRaw = raw
            return null
        }
        if (distanceMeters(from, raw) < MIN_MOVE_METERS) return null
        lastRaw = raw
        return bearing(from, raw)
    }

    override fun registerLocationConsumer(locationConsumer: LocationConsumer) {
        if (consumers.isEmpty()) source.registerLocationConsumer(relay)
        consumers += locationConsumer
    }

    override fun unRegisterLocationConsumer(locationConsumer: LocationConsumer) {
        consumers -= locationConsumer
        if (consumers.isEmpty()) source.unRegisterLocationConsumer(relay)
    }

    private companion object {
        /** Typical phone GPS error plus a lane or two; past this you've genuinely left the line. */
        const val SNAP_METERS = 35.0
        /** Movement needed before a direction is trusted off the line (GPS wobbles a few metres). */
        const val MIN_MOVE_METERS = 6.0
    }
}
