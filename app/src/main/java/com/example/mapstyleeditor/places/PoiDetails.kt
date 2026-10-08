package com.example.mapstyleeditor.places

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.mapstyleeditor.nav.distanceMeters
import com.example.mapstyleeditor.nav.httpGet
import com.mapbox.geojson.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.UUID

/** A POI the user tapped on the map: what the map itself knows about it. */
data class TappedPoi(val name: String, val group: String?, val point: Point)

/**
 * Extra facts about a tapped POI. Everything is optional: Mapbox's place data is thin in many areas
 * (often no phone or hours, almost never a rating), and only well-known places have a Wikipedia photo.
 */
data class PoiDetails(
    val category: String? = null,
    val address: String? = null,
    val phone: String? = null,
    val website: String? = null,
    val rating: Double? = null,
    val hoursToday: String? = null,
    val photo: Bitmap? = null,
    val about: String? = null,
)

/** Looks up [poi] in Mapbox Search and Wikipedia at the same time. */
suspend fun loadPoiDetails(token: String, poi: TappedPoi): PoiDetails = coroutineScope {
    val mapbox = async { mapboxDetails(token, poi) }
    val wiki = async { wikipediaPhoto(poi) }
    val base = mapbox.await() ?: PoiDetails()
    val (photo, about) = wiki.await() ?: (null to null)
    base.copy(photo = photo, about = about)
}

/**
 * Mapbox Search Box: find the POI by name near where it was tapped (suggest), then fetch its record
 * (retrieve). One search session per tap; the free tier covers hundreds a month.
 */
private suspend fun mapboxDetails(token: String, poi: TappedPoi): PoiDetails? = withContext(Dispatchers.IO) {
    runCatching {
        val session = UUID.randomUUID().toString()
        val near = "${poi.point.longitude()},${poi.point.latitude()}"
        val suggest = httpGet(
            "https://api.mapbox.com/search/searchbox/v1/suggest?q=${enc(poi.name)}&proximity=$near" +
                "&types=poi&limit=5&session_token=$session&access_token=$token",
        ) ?: return@runCatching null
        val suggestions = JSONObject(suggest).getJSONArray("suggestions")
        val match = (0 until suggestions.length()).map { suggestions.getJSONObject(it) }
            .firstOrNull { sameName(it.optString("name"), poi.name) }
            ?: return@runCatching null
        val retrieve = httpGet(
            "https://api.mapbox.com/search/searchbox/v1/retrieve/${match.getString("mapbox_id")}" +
                "?session_token=$session&access_token=$token",
        ) ?: return@runCatching null
        val feature = JSONObject(retrieve).getJSONArray("features").getJSONObject(0)
        val coords = feature.getJSONObject("geometry").getJSONArray("coordinates")
        // A same-named place across town isn't this one.
        if (distanceMeters(poi.point, Point.fromLngLat(coords.getDouble(0), coords.getDouble(1))) > 400) {
            return@runCatching null
        }
        val props = feature.getJSONObject("properties")
        val meta = props.optJSONObject("metadata") ?: JSONObject()
        PoiDetails(
            category = props.optJSONArray("poi_category")?.optString(0)?.replaceFirstChar { it.uppercase() },
            address = props.optString("address").ifBlank { props.optString("full_address") }.ifBlank { null },
            phone = meta.optString("phone").ifBlank { null },
            website = meta.optString("website").ifBlank { null },
            rating = meta.optDouble("rating").takeIf { !it.isNaN() },
            hoursToday = meta.optJSONObject("open_hours")?.let(::hoursToday),
        )
    }.getOrNull()
}

/**
 * Wikipedia article near the POI with the same name: its lead photo and one-line description.
 * Geosearch keeps it to articles actually located there.
 */
private suspend fun wikipediaPhoto(poi: TappedPoi): Pair<Bitmap?, String?>? = withContext(Dispatchers.IO) {
    runCatching {
        val json = httpGet(
            "https://en.wikipedia.org/w/api.php?action=query&format=json&generator=geosearch" +
                "&ggscoord=${poi.point.latitude()}%7C${poi.point.longitude()}&ggsradius=250&ggslimit=10" +
                "&prop=pageimages%7Cdescription&piprop=thumbnail&pithumbsize=720",
            userAgent = USER_AGENT,
        ) ?: return@runCatching null
        val pages = JSONObject(json).optJSONObject("query")?.optJSONObject("pages") ?: return@runCatching null
        val page = pages.keys().asSequence().map { pages.getJSONObject(it) }
            .firstOrNull { sameName(it.optString("title"), poi.name) }
            ?: return@runCatching null
        val thumb = page.optJSONObject("thumbnail")?.optString("source")
        val photo = thumb?.let { url ->
            (URL(url).openConnection().apply { setRequestProperty("User-Agent", USER_AGENT) }).getInputStream()
                .use(BitmapFactory::decodeStream)
        }
        photo to page.optString("description").ifBlank { null }?.replaceFirstChar { it.uppercase() }
    }.getOrNull()
}

/** "Open today 9:00 AM – 5:00 PM", from Mapbox's weekly periods (day 0 = Monday). */
private fun hoursToday(openHours: JSONObject): String? {
    val periods = openHours.optJSONArray("periods") ?: return null
    // Calendar: Sunday = 1 ... Saturday = 7. Mapbox: Monday = 0 ... Sunday = 6.
    val today = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7
    val ranges = (0 until periods.length()).map { periods.getJSONObject(it) }
        .filter { it.optJSONObject("open")?.optInt("day") == today }
        .mapNotNull { p ->
            val open = p.optJSONObject("open")?.optString("time") ?: return@mapNotNull null
            val close = p.optJSONObject("close")?.optString("time") ?: return@mapNotNull null
            "${clock(open)} – ${clock(close)}"
        }
    return if (ranges.isEmpty()) "Closed today" else "Open today " + ranges.joinToString(", ")
}

/** "0930" -> "9:30 AM". */
private fun clock(hhmm: String): String {
    val h = hhmm.take(2).toIntOrNull() ?: return hhmm
    val m = hhmm.drop(2).take(2)
    val suffix = if (h < 12 || h == 24) "AM" else "PM"
    val h12 = when { h == 0 || h == 24 -> 12; h > 12 -> h - 12; else -> h }
    return "$h12:$m $suffix"
}

/** Loose name match: "The Museum of Modern Art" vs "Museum of Modern Art (MoMA)". */
private fun sameName(a: String, b: String): Boolean {
    fun norm(s: String) = s.lowercase().replace(Regex("\\(.*?\\)"), "").replace(Regex("[^a-z0-9]"), "").removePrefix("the")
    val x = norm(a)
    val y = norm(b)
    return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.contains(y) || y.contains(x))
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** Wikimedia asks API clients to identify themselves. */
private const val USER_AGENT = "MinMap/1.0 (personal Android app)"
