package com.example.mapstyleeditor.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import androidx.car.app.CarContext
import com.example.mapstyleeditor.ColorTarget
import com.example.mapstyleeditor.DEFAULT_POI_DENSITY
import com.example.mapstyleeditor.MapStyle
import com.example.mapstyleeditor.VisibilityTarget
import com.example.mapstyleeditor.nav.ROUTE_LAYER_IDS
import com.example.mapstyleeditor.nav.Route
import com.example.mapstyleeditor.nav.RouteSnappingProvider
import com.example.mapstyleeditor.nav.RouteTrimmer
import com.mapbox.bindgen.Value
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.ScreenCoordinate
import com.mapbox.maps.Style
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.androidauto.DefaultMapboxCarMapGestureHandler
import com.mapbox.maps.extension.androidauto.MapboxCarMapGestureHandler
import com.mapbox.maps.extension.androidauto.MapboxCarMapObserver
import com.mapbox.maps.extension.androidauto.MapboxCarMapSurface
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.locationcomponent.LocationProvider
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorBearingChangedListener
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateBearing
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateOptions
import com.mapbox.maps.plugin.viewport.ViewportStatus
import com.mapbox.maps.plugin.viewport.viewport
import org.json.JSONObject

/**
 * The map on the car screen: Mapbox Standard in your MinMap style (from the phone's style editor,
 * kept in step when you change it there), a dark map when the car is in night mode, your position,
 * and the drive's route line. The camera follows you unless you pan the map.
 */
class CarMap(private val carContext: CarContext, private val onFollowingChanged: () -> Unit) : MapboxCarMapObserver {
    /** Where you are and which way you're heading, from the map's location puck. */
    @Volatile var here: Point? = null
        private set
    @Volatile var bearing: Double? = null
        private set

    /** False after you've panned the map; [follow] picks it back up. */
    var following = true
        private set

    private var surface: MapboxCarMapSurface? = null
    private var visibleInsets = EdgeInsets(0.0, 0.0, 0.0, 0.0)
    private var driving = false
    private var route: Route? = null
    /** Zoom steps from the zoom-in/out buttons, on top of each view's normal zoom. */
    private var zoomOffset = 0.0
    private val trimmer = RouteTrimmer()
    private var gps: LocationProvider? = null

    private val stylePrefs = carContext.getSharedPreferences("map_style", Context.MODE_PRIVATE)
    private val styleChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == STYLE_KEY) applyStyle()
    }
    /** Standard's own default for each setting, read from the loaded style. */
    private var defaults: Map<String, Value> = emptyMap()
    private val position = OnIndicatorPositionChangedListener {
        here = it
        trimmer.onPuckMoved(it)
        // Meant to follow, but the camera let go (a style load resets it): pick it back up.
        if (following && surface?.mapSurface?.viewport?.status == ViewportStatus.Idle) follow()
    }
    private val heading = OnIndicatorBearingChangedListener { bearing = it }

    /** Panning, flinging or pinching the map stops it following you. */
    val gestures = object : MapboxCarMapGestureHandler {
        private val default = DefaultMapboxCarMapGestureHandler()
        override fun onScroll(mapboxCarMapSurface: MapboxCarMapSurface, focus: ScreenCoordinate, distanceX: Float, distanceY: Float) {
            stopFollowing()
            default.onScroll(mapboxCarMapSurface, focus, distanceX, distanceY)
        }
        override fun onFling(mapboxCarMapSurface: MapboxCarMapSurface, velocityX: Float, velocityY: Float) {
            stopFollowing()
            default.onFling(mapboxCarMapSurface, velocityX, velocityY)
        }
        override fun onScale(mapboxCarMapSurface: MapboxCarMapSurface, focusX: Float, focusY: Float, scaleFactor: Float) {
            stopFollowing()
            default.onScale(mapboxCarMapSurface, focusX, focusY, scaleFactor)
        }
    }

    override fun onAttached(mapboxCarMapSurface: MapboxCarMapSurface) {
        surface = mapboxCarMapSurface
        trimmer.map = mapboxCarMapSurface.mapSurface.mapboxMap
        // Open on where you last were, not the whole globe, while GPS and the style get going.
        lastKnownPoint()?.let { start ->
            mapboxCarMapSurface.mapSurface.mapboxMap.setCamera(cameraOptions { center(start); zoom(BROWSE_ZOOM); pitch(40.0) })
        }
        stylePrefs.registerOnSharedPreferenceChangeListener(styleChanged)
        loadStyle()
        val location = mapboxCarMapSurface.mapSurface.location
        location.addOnIndicatorPositionChangedListener(position)
        location.addOnIndicatorBearingChangedListener(heading)
        applyLocation()
        follow()
    }

    override fun onDetached(mapboxCarMapSurface: MapboxCarMapSurface) {
        stylePrefs.unregisterOnSharedPreferenceChangeListener(styleChanged)
        val location = mapboxCarMapSurface.mapSurface.location
        location.removeOnIndicatorPositionChangedListener(position)
        location.removeOnIndicatorBearingChangedListener(heading)
        trimmer.map = null
        surface = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect, edgeInsets: EdgeInsets) {
        visibleInsets = edgeInsets
        if (following) follow()
    }

    /**
     * Loads Standard once, with the route line, then [applyStyle]. Later changes (your style on the
     * phone, the car's day/night) only update its settings: Mapbox ignores loading the same style
     * again once the route line has been added to it.
     */
    private fun loadStyle() {
        val map = surface?.mapSurface?.mapboxMap ?: return
        map.loadStyle(Style.STANDARD) { style ->
            @Suppress("UNCHECKED_CAST")
            val schema = map.getStyleImportSchema(BASEMAP).value?.contents as? Map<String, Value>
            defaults = schema.orEmpty().mapNotNull { (key, option) ->
                @Suppress("UNCHECKED_CAST")
                ((option.contents as? Map<String, Value>)?.get("default"))?.let { key to it }
            }.toMap()
            applyStyle()
            // Loading a style resets the camera to the style's own default view; follow again.
            if (following) follow()
            style.addSource(geoJsonSource(ROUTE_SOURCE) {
                lineMetrics(true)
                featureCollection(FeatureCollection.fromFeatures(emptyList()))
            })
            // Above roads, below labels and the puck (Standard's middle slot), as on the phone.
            style.addLayer(lineLayer(ROUTE_LAYER_IDS[0], ROUTE_SOURCE) {
                slot("middle"); lineColor(Color.WHITE); lineWidth(13.0)
                lineCap(LineCap.ROUND); lineJoin(LineJoin.ROUND); lineEmissiveStrength(1.0)
            })
            style.addLayer(lineLayer(ROUTE_LAYER_IDS[1], ROUTE_SOURCE) {
                slot("middle"); lineColor(ROUTE_BLUE); lineWidth(9.0)
                lineCap(LineCap.ROUND); lineJoin(LineJoin.ROUND); lineEmissiveStrength(1.0)
            })
            drawRoute()
        }
    }

    /** Puts your MinMap style on the map (and night lighting when the car is in night mode). */
    fun applyStyle() {
        val map = surface?.mapSurface?.mapboxMap ?: return
        if (map.style == null) return
        map.setStyleImportConfigProperties(BASEMAP, carStyleConfig(stylePrefs.getString(STYLE_KEY, null), carContext.isDarkMode, defaults))
    }

    /** Shows [route] (null clears it) and switches between the browsing and driving views. */
    fun showDrive(route: Route?, driving: Boolean) {
        val viewChanged = driving != this.driving
        this.route = route
        this.driving = driving
        trimmer.route = route
        drawRoute()
        if (viewChanged) {
            zoomOffset = 0.0
            applyLocation()
            follow()
        }
    }

    fun follow() {
        following = true
        val mapSurface = surface?.mapSurface ?: return
        val viewport = mapSurface.viewport
        val options = FollowPuckViewportStateOptions.Builder()
            .zoom((if (driving) DRIVE_ZOOM else BROWSE_ZOOM) + zoomOffset)
            .pitch(if (driving) 60.0 else 40.0)
            .bearing(if (driving) FollowPuckViewportStateBearing.SyncWithLocationPuck else FollowPuckViewportStateBearing.Constant(0.0))
            .padding(followPadding())
            .build()
        viewport.transitionTo(viewport.makeFollowPuckViewportState(options))
        onFollowingChanged()
    }

    fun zoom(by: Double) {
        if (following) {
            zoomOffset = (zoomOffset + by).coerceIn(-6.0, 3.0)
            follow()
            return
        }
        val mapSurface = surface?.mapSurface ?: return
        val zoom = mapSurface.mapboxMap.cameraState.zoom + by
        mapSurface.camera.easeTo(cameraOptions { zoom(zoom) })
    }

    private fun stopFollowing() {
        if (!following) return
        following = false
        surface?.mapSurface?.viewport?.idle()
        onFollowingChanged()
    }

    /** Keeps you inside the part of the screen the car leaves visible; low on it while driving. */
    private fun followPadding(): EdgeInsets {
        if (!driving) return visibleInsets
        val height = surface?.surfaceContainer?.height?.toDouble() ?: return visibleInsets
        val visible = height - visibleInsets.top - visibleInsets.bottom
        return EdgeInsets(visibleInsets.top + visible * 0.45, visibleInsets.left, visibleInsets.bottom, visibleInsets.right)
    }

    /** The phone's most recent position from any location source, without waiting for a fix. */
    @SuppressLint("MissingPermission") // Checked first.
    private fun lastKnownPoint(): Point? {
        val fine = ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val manager = carContext.getSystemService(LocationManager::class.java) ?: return null
        return manager.getProviders(true)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { Point.fromLngLat(it.longitude, it.latitude) }
    }

    private fun applyLocation() {
        val location = surface?.mapSurface?.location ?: return
        location.updateSettings {
            enabled = true
            locationPuck = createDefault2DPuck(withBearing = driving)
            puckBearingEnabled = driving
            puckBearing = PuckBearing.COURSE
        }
        // While driving, positions snap onto the route line (see RouteSnappingProvider).
        val provider = gps ?: location.getLocationProvider()?.also { gps = it } ?: return
        location.setLocationProvider(if (driving) RouteSnappingProvider(provider) { route } else provider)
    }

    private fun drawRoute() {
        val style = surface?.mapSurface?.mapboxMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
        val line = route
        if (line == null) {
            source.featureCollection(FeatureCollection.fromFeatures(emptyList()))
        } else {
            source.feature(Feature.fromGeometry(LineString.fromLngLats(line.points)))
        }
    }

    private companion object {
        const val STYLE_KEY = "mapbox_config"
        const val BASEMAP = "basemap"
        const val ROUTE_SOURCE = "minmap-route"
        const val BROWSE_ZOOM = 15.0
        const val DRIVE_ZOOM = 16.5
        val ROUTE_BLUE = Color.rgb(0x2F, 0x7B, 0xFF)
    }
}

/**
 * Your saved MinMap style as Standard config values: every setting the style editor manages, so a
 * change also undoes what an earlier style set. Settings your style leaves alone get [defaults]
 * (Standard's own); a colour can't be "unset" (Mapbox draws it black). At night (the car in dark
 * mode) a daytime light preset becomes night, so the screen doesn't glare; a style that's already
 * dusk or night stays as it is.
 */
fun carStyleConfig(savedConfig: String?, dark: Boolean, defaults: Map<String, Value> = emptyMap()): HashMap<String, Value> {
    val json = JSONObject(MapStyle.fromConfigJsonOrDefault(savedConfig).toConfigJson())
    val values = HashMap<String, Value>()
    // What the editor can change, back to Standard's defaults unless the style says otherwise.
    for (target in ColorTarget.entries) defaults[target.configKey]?.let { values[target.configKey] = it }
    for (target in VisibilityTarget.entries) values[target.configKey] = Value(true)
    values["lightPreset"] = Value("day")
    values["theme"] = Value("default")
    values["densityPointOfInterestLabels"] = Value(DEFAULT_POI_DENSITY.toLong())
    values["colorModePointOfInterestLabels"] = Value("default")
    for (key in json.keys()) {
        when (val v = json.get(key)) {
            is Boolean -> values[key] = Value(v)
            is Int -> values[key] = Value(v.toLong())
            is Long -> values[key] = Value(v)
            is Number -> values[key] = Value(v.toDouble())
            else -> values[key] = Value(v.toString())
        }
    }
    val light = json.optString("lightPreset", "day")
    if (dark && (light == "day" || light == "dawn")) values["lightPreset"] = Value("night")
    return values
}
