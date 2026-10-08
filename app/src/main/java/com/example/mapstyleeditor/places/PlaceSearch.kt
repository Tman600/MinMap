package com.example.mapstyleeditor.places

import com.example.mapstyleeditor.nav.distanceMeters
import com.example.mapstyleeditor.ui.Place
import com.mapbox.geojson.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

/**
 * One line in the search list. A place or address gets its position when picked ([PlaceSearch.open]);
 * a category ("Coffee Shop") opens into the nearest places of that kind instead.
 */
data class SearchHit(
    val name: String,
    val detail: String,
    /** Metres from you, when known. */
    val distance: Double?,
    val mapboxId: String?,
    /** Set for a whole category of places, e.g. "coffee_shop". */
    val category: String?,
    /** Known straight away for category results; looked up on picking for the rest. */
    val point: Point?,
)

/**
 * Finds places with Mapbox's Search Box: businesses, landmarks and other points of interest as
 * well as addresses and towns (the Geocoding API used before only knew the last two). Typing and
 * then picking a result counts as one search "session" on the user's Mapbox account; category
 * lists are billed per request.
 */
class PlaceSearch(private val token: String) {
    private var session = UUID.randomUUID().toString()

    /** Suggestions for [query], ranked near [near] (where the map is), with distances from [here]. */
    suspend fun suggest(query: String, near: Point?, here: Point?): List<SearchHit> {
        val body = get(
            "suggest",
            "q" to query.trim(), "session_token" to session, "limit" to "8", "language" to language(),
            "proximity" to near?.let(::lngLat), "origin" to here?.let(::lngLat),
        ) ?: return emptyList()
        val list = JSONObject(body).optJSONArray("suggestions") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }.map { s ->
            val isCategory = s.optString("feature_type") == "category"
            SearchHit(
                name = s.optString("name"),
                detail = if (isCategory) "Nearby" else s.optString("full_address").ifBlank { s.optString("place_formatted") },
                distance = s.optDouble("distance").takeIf { !it.isNaN() },
                mapboxId = s.optString("mapbox_id").ifBlank { null },
                category = if (isCategory) s.optJSONArray("poi_category_ids")?.optString(0)?.ifBlank { null } else null,
                point = null,
            )
        }
    }

    /** The nearest places in [category] around [near], closest to [here] first. */
    suspend fun category(category: String, near: Point?, here: Point?): List<SearchHit> {
        val body = get(
            "category/" + URLEncoder.encode(category, "UTF-8"),
            "limit" to "10", "language" to language(),
            "proximity" to near?.let(::lngLat), "origin" to here?.let(::lngLat),
        ) ?: return emptyList()
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { features.getJSONObject(it).toHit(here) }
            .sortedBy { it.distance ?: Double.MAX_VALUE }
    }

    /** The position of a picked hit (looked up if needed), ready to fly to. Ends the session. */
    suspend fun open(hit: SearchHit): Place? {
        hit.point?.let { return Place(hit.name, hit.detail, it.longitude(), it.latitude()) }
        val id = hit.mapboxId ?: return null
        val body = get("retrieve/" + URLEncoder.encode(id, "UTF-8"), "session_token" to session) ?: return null
        session = UUID.randomUUID().toString()
        val feature = JSONObject(body).optJSONArray("features")?.optJSONObject(0) ?: return null
        val point = feature.point() ?: return null
        return Place(hit.name, hit.detail, point.longitude(), point.latitude())
    }

    private fun JSONObject.toHit(here: Point?): SearchHit? {
        val props = optJSONObject("properties") ?: return null
        val point = point() ?: return null
        return SearchHit(
            name = props.optString("name"),
            detail = props.optString("full_address").ifBlank { props.optString("place_formatted") },
            distance = props.optDouble("distance").takeIf { !it.isNaN() } ?: here?.let { distanceMeters(it, point) },
            mapboxId = props.optString("mapbox_id").ifBlank { null },
            category = null,
            point = point,
        )
    }

    private fun JSONObject.point(): Point? {
        val c = optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        return Point.fromLngLat(c.getDouble(0), c.getDouble(1))
    }

    private suspend fun get(path: String, vararg params: Pair<String, String?>): String? = withContext(Dispatchers.IO) {
        runCatching {
            val query = (params.toList() + ("access_token" to token))
                .filter { it.second != null }
                .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
            val conn = URL("https://api.mapbox.com/search/searchbox/v1/$path?$query").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode != HttpURLConnection.HTTP_OK) null
                else conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun lngLat(p: Point) = "${p.longitude()},${p.latitude()}"
    private fun language() = Locale.getDefault().language.ifBlank { "en" }
}

/** "350 ft", "2.4 mi" where miles are used; "350 m", "2.4 km" elsewhere. */
fun formatDistance(meters: Double): String {
    val miles = Locale.getDefault().country in setOf("US", "GB", "LR", "MM")
    return if (miles) {
        val mi = meters / 1609.344
        if (mi < 0.1) "${(meters * 3.28084 / 10).toInt() * 10} ft" else "%.1f mi".format(mi)
    } else {
        if (meters < 1000) "${(meters / 10).toInt() * 10} m" else "%.1f km".format(meters / 1000)
    }
}
