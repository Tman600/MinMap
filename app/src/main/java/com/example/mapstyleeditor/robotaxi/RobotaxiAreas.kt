package com.example.mapstyleeditor.robotaxi

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Color
import com.example.mapstyleeditor.nav.httpGet
import com.mapbox.geojson.Feature
import com.mapbox.geojson.Geometry
import com.mapbox.geojson.MultiPolygon
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Robotaxi operators we draw: the colour each one's service areas get on the map, and its rider
 * app on Google Play (package names checked against the Play Store listings).
 */
enum class RobotaxiProvider(val key: String, val label: String, val color: Color, val appPackage: String) {
    WAYMO("waymo", "Waymo", Color(0xFF1E9E8F), "com.waymo.carapp"),
    TESLA("tesla", "Tesla", Color(0xFFD63B3B), "com.tesla.riders"),
    ZOOX("zoox", "Zoox", Color(0xFF6B5BD2), "com.zoox.android.ride"),
}

/** Opens the operator's rider app, or its Play Store page if it isn't installed. */
fun openRobotaxiApp(context: Context, provider: RobotaxiProvider) {
    val launch = context.packageManager.getLaunchIntentForPackage(provider.appPackage)
        ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${provider.appPackage}"))
    try {
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${provider.appPackage}")),
        )
    }
}

/** Operators whose service area contains [point]. Rides have to start and end inside one. */
fun providersCovering(areas: Map<RobotaxiProvider, List<Feature>>, point: Point): List<RobotaxiProvider> =
    areas.filter { (_, features) -> features.any { contains(it.geometry(), point) } }.keys.toList()

private fun contains(geometry: Geometry?, p: Point): Boolean = when (geometry) {
    is Polygon -> inPolygon(geometry.coordinates(), p)
    is MultiPolygon -> geometry.coordinates().any { inPolygon(it, p) }
    else -> false
}

/** Even-odd ray casting over the outer ring and any holes: inside the outer ring and not in a hole. */
private fun inPolygon(rings: List<List<Point>>, p: Point): Boolean =
    rings.foldIndexed(false) { _, inside, ring -> if (inRing(ring, p)) !inside else inside }

private fun inRing(ring: List<Point>, p: Point): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val a = ring[i]
        val b = ring[j]
        if ((a.latitude() > p.latitude()) != (b.latitude() > p.latitude()) &&
            p.longitude() < (b.longitude() - a.longitude()) * (p.latitude() - a.latitude()) / (b.latitude() - a.latitude()) + a.longitude()
        ) {
            inside = !inside
        }
        j = i
    }
    return inside
}

/**
 * Robotaxi service areas from the community-maintained Robotaxi Tracker dataset
 * (github.com/Robotaxi-Tracker/robotaxi-service-areas, CC BY 4.0, credit shown on the map).
 *
 * The app ships a snapshot so areas show offline and on first launch; a fresh copy is fetched at
 * most once a day and kept for next time. Operators expand often, so the snapshot ages quickly.
 */
object RobotaxiAreas {
    private const val URL =
        "https://raw.githubusercontent.com/Robotaxi-Tracker/robotaxi-service-areas/main/dist/service-areas.json"
    private const val ASSET = "robotaxi-service-areas.json"
    private const val CACHE = "robotaxi-service-areas.json"
    private const val REFRESH_MS = 24 * 60 * 60 * 1000L

    /** The best copy available right now: today's download, else the last one, else the bundled snapshot. */
    suspend fun load(context: Context): Map<RobotaxiProvider, List<Feature>> = withContext(Dispatchers.IO) {
        val cache = File(context.filesDir, CACHE)
        val stale = !cache.exists() || System.currentTimeMillis() - cache.lastModified() > REFRESH_MS
        if (stale) {
            httpGet(URL)?.takeIf { runCatching { parse(it) }.getOrNull()?.isNotEmpty() == true }?.let(cache::writeText)
        }
        val json = cache.takeIf { it.exists() }?.readText()
            ?: context.assets.open(ASSET).bufferedReader().use { it.readText() }
        runCatching { parse(json) }.getOrDefault(emptyMap())
    }

    private fun parse(json: String): Map<RobotaxiProvider, List<Feature>> {
        val areas = JSONObject(json).getJSONArray("areas")
        return (0 until areas.length()).map { areas.getJSONObject(it) }
            .filterNot { it.optBoolean("isTestRegion", false) }
            .mapNotNull { area ->
                val provider = RobotaxiProvider.entries.find { it.key == area.optString("provider") } ?: return@mapNotNull null
                val feature = Feature.fromJson(area.getJSONObject("boundary").toString())
                feature.addStringProperty("name", "${provider.label} · ${area.optString("name")}")
                provider to feature
            }
            .groupBy({ it.first }, { it.second })
    }
}
