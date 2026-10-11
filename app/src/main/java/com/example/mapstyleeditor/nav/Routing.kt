package com.example.mapstyleeditor.nav

import com.example.mapstyleeditor.account.MapboxUsage
import com.mapbox.geojson.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One maneuver on our route: the road it turns onto, and how far along the route it is. Routes
 * fetched with guidance (MinMap navigating by itself) also carry what to show and say for it.
 */
data class RouteStep(
    val names: List<String>,
    val alongMeters: Double,
    /** Mapbox's written instruction, e.g. "Turn right onto Main Street". */
    val instruction: String = "",
    /** Mapbox maneuver type ("turn", "arrive", "roundabout"...) and modifier ("left", "slight right"...). */
    val type: String = "",
    val modifier: String? = null,
    /** What to say while driving this step, and where (meters along the route) to say it. */
    val voice: List<VoiceCue> = emptyList(),
    /** Which exit to take, for roundabouts. */
    val exit: Int? = null,
    /** "right" where traffic drives on the right (roundabouts then turn counterclockwise). */
    val drivingSide: String? = null,
)

data class VoiceCue(val alongMeters: Double, val text: String)

/**
 * A Mapbox route line plus its maneuvers. [cumulative] is the distance from the start to each point;
 * [segmentSeconds], when known, is the expected travel time (with traffic) of each piece between points.
 */
class Route(val points: List<Point>, val steps: List<RouteStep>, private val segmentSeconds: DoubleArray? = null) {
    val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + distanceMeters(points[i - 1], points[i])
    }

    /** Expected time left from [alongMeters] to the end of the line, in seconds. Null without timings. */
    fun secondsLeft(alongMeters: Double): Double? {
        val seconds = segmentSeconds?.takeIf { it.size == points.size - 1 } ?: return null
        var total = 0.0
        for (i in seconds.indices) {
            val start = cumulative[i]
            val end = cumulative[i + 1]
            total += when {
                end <= alongMeters -> 0.0
                start >= alongMeters -> seconds[i]
                else -> seconds[i] * (end - alongMeters) / (end - start).coerceAtLeast(1e-6) // Partway through.
            }
        }
        return total
    }

    /** Where [p] sits relative to the line: how far along it, and how far off it, in meters. */
    fun locate(p: Point): Pair<Double, Double> {
        var bestAlong = 0.0
        var bestOff = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val (t, off) = projectOntoSegment(p, points[i], points[i + 1])
            if (off < bestOff) {
                bestOff = off
                bestAlong = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
            }
        }
        return bestAlong to bestOff
    }

    /**
     * Like [locate], but only checks segments near [hint] (the segment found last time), which is all
     * that's needed when following a moving puck frame by frame. Falls back to a full search if the
     * point isn't near that stretch. Returns (meters along, segment index).
     */
    fun locateNear(p: Point, hint: Int): Pair<Double, Int> {
        fun search(from: Int, to: Int): Triple<Double, Double, Int> {
            var bestAlong = 0.0
            var bestOff = Double.MAX_VALUE
            var bestIndex = from
            for (i in from until to) {
                val (t, off) = projectOntoSegment(p, points[i], points[i + 1])
                if (off < bestOff) {
                    bestOff = off
                    bestAlong = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
                    bestIndex = i
                }
            }
            return Triple(bestAlong, bestOff, bestIndex)
        }
        val last = points.size - 1
        val near = search((hint - 3).coerceIn(0, last), (hint + 80).coerceIn(0, last))
        val (along, _, index) = if (near.second < 60.0) near else search(0, last)
        return along to index
    }

    /** Direction of travel (compass degrees) on the line at [meters] along it. */
    fun bearingAt(meters: Double): Double {
        val i = cumulative.indexOfFirst { it >= meters }.coerceIn(1, points.size - 1)
        return bearing(points[i - 1], points[i])
    }

    /** The point [meters] along the line (clamped to its ends). */
    fun pointAt(meters: Double): Point {
        if (meters <= 0) return points.first()
        val i = cumulative.indexOfFirst { it >= meters }.takeIf { it > 0 } ?: return points.last()
        val t = (meters - cumulative[i - 1]) / (cumulative[i] - cumulative[i - 1]).coerceAtLeast(1e-6)
        val a = points[i - 1]
        val b = points[i]
        return Point.fromLngLat(a.longitude() + t * (b.longitude() - a.longitude()), a.latitude() + t * (b.latitude() - a.latitude()))
    }
}

/**
 * Mapbox driving route (with live traffic) through [stops]. Stops between the first and last are
 * pass-through points, not destinations. [startBearing] keeps the route from starting with a U-turn;
 * [stopBearings] (stop index to compass heading) makes it pass those stops travelling that way, so a
 * point on a street isn't reached from the wrong side and then looped back from.
 */
suspend fun fetchRoute(
    token: String,
    stops: List<Point>,
    startBearing: Double? = null,
    stopBearings: Map<Int, Double> = emptyMap(),
    /** Also ask for spoken instructions (same request, no extra cost), for MinMap's own guidance. */
    guidance: Boolean = false,
): Route? = withContext(Dispatchers.IO) {
    runCatching {
        val coords = stops.joinToString(";") { "${it.longitude()},${it.latitude()}" }
        val headings = stops.indices.map { i -> (if (i == 0) startBearing else stopBearings[i]) }
        val bearings = if (headings.all { it == null }) "" else {
            "&bearings=" + headings.joinToString(";") { h -> h?.let { "${((it % 360 + 360) % 360).toInt()},45" }.orEmpty() }
        }
        val waypoints = if (stops.size > 2) "&waypoints=0;${stops.size - 1}" else ""
        val voice = if (!guidance) "" else {
            "&voice_instructions=true&voice_units=${if (usesMiles()) "imperial" else "metric"}&language=${Locale.getDefault().language}"
        }
        val json = httpGet(
            "https://api.mapbox.com/directions/v5/mapbox/driving-traffic/$coords" +
                "?geometries=geojson&overview=full&steps=true&annotations=duration$bearings$waypoints$voice&access_token=$token",
        ) ?: return@runCatching null
        parseRoute(json)
    }.getOrNull()
}

/** A Directions API response's first route as a [Route]; null if it has none. */
internal fun parseRoute(json: String): Route? {
    val route = JSONObject(json).getJSONArray("routes").optJSONObject(0) ?: return null
    val points = route.getJSONObject("geometry").getJSONArray("coordinates").toPoints()
    val shell = Route(points, emptyList())
    val steps = mutableListOf<RouteStep>()
    val seconds = mutableListOf<Double>()
    val legs = route.getJSONArray("legs")
    for (l in 0 until legs.length()) {
        legs.getJSONObject(l).optJSONObject("annotation")?.optJSONArray("duration")?.let { d ->
            for (i in 0 until d.length()) seconds += d.getDouble(i)
        }
        val legSteps = legs.getJSONObject(l).getJSONArray("steps")
        for (s in 0 until legSteps.length()) {
            val step = legSteps.getJSONObject(s)
            val maneuver = step.getJSONObject("maneuver")
            val loc = maneuver.getJSONArray("location")
            val names = listOf("name", "ref", "destinations", "exits")
                .mapNotNull { step.optString(it).takeIf(String::isNotBlank) }
            steps += RouteStep(
                names = names,
                alongMeters = shell.locate(Point.fromLngLat(loc.getDouble(0), loc.getDouble(1))).first,
                instruction = maneuver.optString("instruction"),
                type = maneuver.optString("type"),
                modifier = maneuver.optString("modifier").takeIf(String::isNotBlank),
                exit = maneuver.optInt("exit", 0).takeIf { it > 0 },
                drivingSide = step.optString("driving_side").takeIf(String::isNotBlank),
                // Placed once every step's start is known (a cue counts back from its step's end).
                voice = step.optJSONArray("voiceInstructions")?.let { cues ->
                    (0 until cues.length()).map { cues.getJSONObject(it) }.map {
                        VoiceCue(it.getDouble("distanceAlongGeometry"), it.getString("announcement"))
                    }
                }.orEmpty(),
            )
        }
    }
    // Voice cues come as "meters before the end of this step"; make them meters along the route.
    val total = shell.cumulative.last()
    val placed = steps.mapIndexed { i, step ->
        val end = steps.getOrNull(i + 1)?.alongMeters ?: total
        step.copy(voice = step.voice.map { it.copy(alongMeters = (end - it.alongMeters).coerceAtLeast(step.alongMeters)) })
    }
    return Route(points, placed, seconds.toDoubleArray())
}

/** Whether distances are given in miles and feet here (US, UK, Liberia, Myanmar), else kilometers. */
fun usesMiles(): Boolean = Locale.getDefault().country in setOf("US", "GB", "LR", "MM")

/** A distance as shown in the turn card: "500 ft" / "0.3 mi", or "200 m" / "1.2 km". */
fun formatDistance(meters: Double): String = if (usesMiles()) {
    val miles = meters / 1609.344
    when {
        miles < 0.1 -> "${(meters / 0.3048 / 50).roundToInt().coerceAtLeast(1) * 50} ft"
        miles < 10 -> String.format(Locale.US, "%.1f mi", miles)
        else -> "${miles.roundToInt()} mi"
    }
} else {
    when {
        meters < 1000 -> "${(meters / 10).roundToInt().coerceAtLeast(1) * 10} m"
        meters < 10_000 -> String.format(Locale.US, "%.1f km", meters / 1000)
        else -> "${(meters / 1000).roundToInt()} km"
    }
}

/** Current driving time (with traffic) from [from] to [to], in whole minutes. Null if it can't be had. */
suspend fun fetchDriveMinutes(token: String, from: Point, to: Point): Int? = withContext(Dispatchers.IO) {
    runCatching {
        val json = httpGet(
            "https://api.mapbox.com/directions/v5/mapbox/driving-traffic/" +
                "${from.longitude()},${from.latitude()};${to.longitude()},${to.latitude()}" +
                "?overview=false&access_token=$token",
        ) ?: return@runCatching null
        val seconds = JSONObject(json).getJSONArray("routes").getJSONObject(0).getDouble("duration")
        (seconds / 60).roundToInt().coerceAtLeast(1)
    }.getOrNull()
}

/**
 * The point on a road called [street] closest to [near], within [radiusMeters]. Uses Mapbox
 * Tilequery against the Streets road data. Null if no road by that name is close enough.
 */
suspend fun snapToStreet(token: String, near: Point, street: String, radiusMeters: Int): Point? = withContext(Dispatchers.IO) {
    runCatching {
        val json = httpGet(
            "https://api.mapbox.com/v4/mapbox.mapbox-streets-v8/tilequery/${near.longitude()},${near.latitude()}.json" +
                "?radius=$radiusMeters&limit=25&layers=road&geometry=linestring&access_token=$token",
        ) ?: return@runCatching null
        val features = JSONObject(json).getJSONArray("features")
        (0 until features.length()).map { features.getJSONObject(it) }
            .filter { f ->
                val props = f.getJSONObject("properties")
                sameRoad(street, listOf(props.optString("name"), props.optString("ref")))
            }
            .minByOrNull { it.getJSONObject("properties").getJSONObject("tilequery").getDouble("distance") }
            ?.getJSONObject("geometry")?.getJSONArray("coordinates")
            ?.let { Point.fromLngLat(it.getDouble(0), it.getDouble(1)) }
    }.getOrNull()
}

/** Whether Google's road name and any of Mapbox's names for a road refer to the same road. */
fun sameRoad(google: String, mapboxNames: List<String>): Boolean {
    val g = normalizeRoad(google)
    if (g.isEmpty()) return false
    return mapboxNames.map(::normalizeRoad).any { m -> m.isNotEmpty() && (m == g || m.contains(g) || g.contains(m)) }
}

private val ROAD_WORDS = mapOf(
    "street" to "st", "avenue" to "ave", "road" to "rd", "drive" to "dr", "boulevard" to "blvd",
    "lane" to "ln", "court" to "ct", "place" to "pl", "highway" to "hwy", "parkway" to "pkwy",
    "terrace" to "ter", "circle" to "cir", "expressway" to "expy", "turnpike" to "tpke", "pike" to "pk",
    "north" to "n", "south" to "s", "east" to "e", "west" to "w", "route" to "rt", "interstate" to "i",
)

/** "N Main Street" and "North Main St" -> "nmainst"; "I-95 N" and "I 95" -> "i95n"/"i95". */
fun normalizeRoad(name: String): String = name.lowercase()
    .replace(Regex("[^a-z0-9 ]"), " ")
    .split(' ').filter(String::isNotBlank)
    .joinToString("") { ROAD_WORDS[it] ?: it }

/** Initial compass bearing from [a] to [b], in degrees 0-360. */
fun bearing(a: Point, b: Point): Double {
    val lat1 = Math.toRadians(a.latitude())
    val lat2 = Math.toRadians(b.latitude())
    val dLng = Math.toRadians(b.longitude() - a.longitude())
    val y = sin(dLng) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/** The point [meters] from [from] heading [bearingDeg] (short distances; flat-earth approximation). */
fun offset(from: Point, bearingDeg: Double, meters: Double): Point {
    val rad = Math.toRadians(bearingDeg)
    val dLat = meters * cos(rad) / 111_320.0
    val dLng = meters * sin(rad) / (111_320.0 * cos(Math.toRadians(from.latitude())))
    return Point.fromLngLat(from.longitude() + dLng, from.latitude() + dLat)
}

/**
 * How far a Google instruction turns from straight ahead, in degrees (negative is left), or null
 * for a U-turn, where a single point can't say which way to go.
 */
fun turnAngle(instruction: String): Double? {
    val s = instruction.lowercase()
    return when {
        "u-turn" in s || "make a u" in s -> null
        "sharp left" in s -> -135.0
        "sharp right" in s -> 135.0
        "slight left" in s || "keep left" in s || "bear left" in s -> -35.0
        "slight right" in s || "keep right" in s || "bear right" in s -> 35.0
        "left" in s -> -90.0
        "right" in s -> 90.0
        else -> 0.0 // "Continue onto…", "Merge onto…", ramps: carry on in roughly the same direction.
    }
}

fun distanceMeters(a: Point, b: Point): Double {
    val lat1 = a.latitude() * PI / 180
    val lat2 = b.latitude() * PI / 180
    val dLat = lat2 - lat1
    val dLng = (b.longitude() - a.longitude()) * PI / 180
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * 6_371_000 * asin(sqrt(h))
}

/** Projects [p] onto segment a-b on a local flat approximation: (fraction along, meters off). */
private fun projectOntoSegment(p: Point, a: Point, b: Point): Pair<Double, Double> {
    val kx = cos(a.latitude() * PI / 180) * 111_320.0
    val ky = 110_540.0
    val bx = (b.longitude() - a.longitude()) * kx
    val by = (b.latitude() - a.latitude()) * ky
    val px = (p.longitude() - a.longitude()) * kx
    val py = (p.latitude() - a.latitude()) * ky
    val len2 = bx * bx + by * by
    val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
    val dx = px - t * bx
    val dy = py - t * by
    return t to sqrt(dx * dx + dy * dy)
}

private fun JSONArray.toPoints() = (0 until length()).map { getJSONArray(it).let { c -> Point.fromLngLat(c.getDouble(0), c.getDouble(1)) } }

internal fun httpGet(url: String, userAgent: String? = null): String? {
    val conn = URL(url).openConnection() as HttpURLConnection
    return try {
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        userAgent?.let { conn.setRequestProperty("User-Agent", it) }
        MapboxUsage.record(url, conn.responseCode)
        if (conn.responseCode != HttpURLConnection.HTTP_OK) null else conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}
