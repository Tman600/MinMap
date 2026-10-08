package com.example.mapstyleeditor.learn

import android.content.Context
import androidx.core.content.edit
import com.example.mapstyleeditor.nav.distanceMeters
import com.mapbox.geojson.Point
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/** One drive started in the app: where to, when, from where, and (once it ends) the roads taken. */
data class Trip(
    val name: String,
    val destination: Point,
    val startedAt: Long,
    val origin: Point?,
    val path: List<Point>? = null,
)

/** A place the app expects you might head to now. */
data class Suggestion(val name: String, val point: Point, val visits: Int)

/**
 * The drives you've started, kept only on this phone (SharedPreferences), newest last. Learning
 * comes only from drives started in the app: there's no background location tracking.
 */
class TripHistory(context: Context) {
    private val prefs = context.getSharedPreferences("trip_history", Context.MODE_PRIVATE)

    fun all(): List<Trip> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Trip(
                name = o.getString("name"),
                destination = Point.fromLngLat(o.getDouble("lng"), o.getDouble("lat")),
                startedAt = o.getLong("at"),
                origin = if (o.has("fromLat")) Point.fromLngLat(o.getDouble("fromLng"), o.getDouble("fromLat")) else null,
                path = o.optJSONArray("path")?.let { arr ->
                    (0 until arr.length()).map { j -> arr.getJSONArray(j).let { Point.fromLngLat(it.getDouble(0), it.getDouble(1)) } }
                },
            )
        }
    }.getOrDefault(emptyList())

    fun record(trip: Trip) = save((all() + trip).takeLast(MAX_TRIPS))

    /** Adds the roads actually driven to the trip that started at [startedAt]. */
    fun attachPath(startedAt: Long, path: List<Point>) =
        save(all().map { if (it.startedAt == startedAt) it.copy(path = path) else it })

    /** Drops every trip to the place at [point], so it stops being suggested. */
    fun forget(point: Point) = save(all().filterNot { distanceMeters(it.destination, point) < SAME_PLACE_METERS })

    private fun save(trips: List<Trip>) {
        val arr = JSONArray()
        trips.forEach { t ->
            arr.put(
                JSONObject()
                    .put("name", t.name)
                    .put("lat", t.destination.latitude())
                    .put("lng", t.destination.longitude())
                    .put("at", t.startedAt)
                    .apply {
                        t.origin?.let { put("fromLat", it.latitude()).put("fromLng", it.longitude()) }
                        t.path?.let { path ->
                            put("path", JSONArray(path.map { p -> JSONArray().put(p.longitude()).put(p.latitude()) }))
                        }
                    },
            )
        }
        prefs.edit { putString(KEY, arr.toString()) }
    }

    private companion object {
        const val KEY = "trips"
        // With simplified paths (~100-300 points each) this stays a few MB at most.
        const val MAX_TRIPS = 300
    }
}

/**
 * Where you're likely headed now, best first. Each past trip votes for its destination, weighted by
 * how close its start time was to now (time of day), whether it was the same kind of day (weekday
 * or weekend, same weekday counts extra), whether it started near where you are, and how recent
 * it was. Places need at least two trips and a solid score, and the place you're at is skipped.
 */
fun suggestDestinations(trips: List<Trip>, now: Calendar, here: Point?, limit: Int = 2): List<Suggestion> {
    val nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    val nowDay = now.get(Calendar.DAY_OF_WEEK)

    // Group trips to the same place (destinations within ~150 m of each other).
    val places = mutableListOf<MutableList<Trip>>()
    for (trip in trips) {
        places.firstOrNull { distanceMeters(it.first().destination, trip.destination) < SAME_PLACE_METERS }?.add(trip)
            ?: places.add(mutableListOf(trip))
    }

    return places
        .filter { it.size >= 2 }
        .filter { place -> here == null || distanceMeters(place.last().destination, here) > AT_PLACE_METERS }
        .map { place ->
            val score = place.sumOf { trip ->
                val then = Calendar.getInstance().apply { timeInMillis = trip.startedAt }
                val minute = then.get(Calendar.HOUR_OF_DAY) * 60 + then.get(Calendar.MINUTE)
                val gap = abs(minute - nowMinute).let { min(it, 24 * 60 - it) }
                val time = exp(-(gap / 75.0).pow(2))
                val day = then.get(Calendar.DAY_OF_WEEK)
                val dayMatch = when {
                    day == nowDay -> 1.3
                    isWeekend(day) == isWeekend(nowDay) -> 1.0
                    else -> 0.3
                }
                val origin = when {
                    here == null || trip.origin == null -> 0.7
                    distanceMeters(trip.origin, here) < 2_000 -> 1.0
                    else -> 0.35
                }
                val ageDays = (now.timeInMillis - trip.startedAt) / 86_400_000.0
                val recency = 0.5.pow(ageDays / 45.0)
                time * dayMatch * origin * recency
            }
            place to score
        }
        .filter { (_, score) -> score >= MIN_SCORE }
        .sortedByDescending { (_, score) -> score }
        .take(limit)
        .map { (place, _) -> Suggestion(place.last().name, place.last().destination, place.size) }
}

private fun isWeekend(day: Int) = day == Calendar.SATURDAY || day == Calendar.SUNDAY

private const val SAME_PLACE_METERS = 150.0
private const val AT_PLACE_METERS = 300.0
/** Roughly: two recent trips at about this time on this kind of day. */
private const val MIN_SCORE = 0.8
