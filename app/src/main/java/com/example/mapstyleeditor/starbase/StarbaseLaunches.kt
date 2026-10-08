package com.example.mapstyleeditor.starbase

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * Starship flights from Starbase, from The Space Devs' free Launch Library 2: the next (or current)
 * one, and the last one flown (for replays). It allows 15 requests an hour without a key, so answers
 * are cached on the phone and only re-asked as often as a launch is near: every few minutes around
 * liftoff, rarely otherwise.
 */
class StarbaseLaunches(context: Context) {
    private val prefs = context.getSharedPreferences("starbase_launches", Context.MODE_PRIVATE)

    /** The launch to show now: from the cache, refreshed first if it's due. */
    suspend fun current(now: Long = System.currentTimeMillis()): StarshipLaunch? {
        val cached = prefs.getString(KEY_BODY, null)?.let(::parse)
        val due = now - prefs.getLong(KEY_AT, 0) > refreshAfter(cached, now)
        if (!due || now - prefs.getLong(KEY_FAILED_AT, 0) < RETRY_AFTER_FAILURE_MS) return cached
        // Launches from four hours ago on, so a flight in progress (or a booster just caught) is
        // still found after it has lifted off.
        val since = Instant.ofEpochMilli(now - 4 * HOUR_MS).toString()
        val body = fetch("launches/?search=Starship&mode=detailed&ordering=net&limit=5&net__gte=" + URLEncoder.encode(since, "UTF-8"))
            ?: return cached.also { prefs.edit { putLong(KEY_FAILED_AT, now) } }
        prefs.edit {
            putString(KEY_BODY, body)
            putLong(KEY_AT, now)
        }
        return parse(body)
    }

    /** The most recent flight that has flown from Starbase, for a replay. Refreshed every 6 hours. */
    suspend fun lastFlight(now: Long = System.currentTimeMillis()): StarshipLaunch? {
        val cached = prefs.getString(KEY_LAST_BODY, null)?.let(::parse)
        if (cached != null && now - prefs.getLong(KEY_LAST_AT, 0) < 6 * HOUR_MS) return cached
        if (now - prefs.getLong(KEY_FAILED_AT, 0) < RETRY_AFTER_FAILURE_MS) return cached
        val body = fetch("launches/previous/?search=Starship&mode=detailed&limit=5")
            ?: return cached.also { prefs.edit { putLong(KEY_FAILED_AT, now) } }
        prefs.edit {
            putString(KEY_LAST_BODY, body)
            putLong(KEY_LAST_AT, now)
        }
        return parse(body)
    }

    /** How long a cached answer holds: short around a launch, when its time and status move. */
    private fun refreshAfter(launch: StarshipLaunch?, now: Long): Long {
        launch ?: return 6 * HOUR_MS
        val until = launch.net - now
        return when {
            until in -15 * MINUTE_MS..60 * MINUTE_MS -> 3 * MINUTE_MS
            abs(until) < 36 * HOUR_MS -> 30 * MINUTE_MS
            else -> 6 * HOUR_MS
        }
    }

    private suspend fun fetch(query: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("https://ll.thespacedevs.com/2.3.0/$query").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", "MinMap/1.0 (personal Android app)")
                if (conn.responseCode != HttpURLConnection.HTTP_OK) null
                else conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private companion object {
        const val KEY_BODY = "body"
        const val KEY_AT = "fetched_at"
        const val KEY_LAST_BODY = "last_body"
        const val KEY_LAST_AT = "last_fetched_at"
        const val KEY_FAILED_AT = "failed_at"
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
        const val RETRY_AFTER_FAILURE_MS = 10 * MINUTE_MS
    }
}

/** The first listed launch from one of Starbase's orbital pads. */
internal fun parse(body: String): StarshipLaunch? = runCatching {
    val results = JSONObject(body).getJSONArray("results")
    (0 until results.length()).asSequence().map { results.getJSONObject(it) }.firstNotNullOfOrNull { launch ->
        val padId = launch.optJSONObject("pad")?.optInt("id") ?: return@firstNotNullOfOrNull null
        val pad = Pad.entries.find { it.launchLibraryId == padId } ?: return@firstNotNullOfOrNull null
        val landing = launch.optJSONObject("rocket")?.optJSONArray("launcher_stage")
            ?.optJSONObject(0)?.optJSONObject("landing")
        val type = landing?.optJSONObject("type")?.optString("name").orEmpty()
        val place = landing?.optJSONObject("landing_location") ?: landing?.optJSONObject("location")
        val placeName = place?.optString("name").orEmpty()
        // A tower catch is listed as "Return to Launch Site" at the launch mount ("OLM-A"), a
        // splashdown as "Ocean" in the Gulf.
        val catch = type.contains("Return to Launch Site", ignoreCase = true) ||
            place?.optString("abbrev").orEmpty().startsWith("OLM") ||
            placeName.contains("Tower", ignoreCase = true) || placeName.contains("Mechazilla", ignoreCase = true)
        StarshipLaunch(
            name = shortName(launch.optString("name")),
            net = Instant.parse(launch.getString("net")).toEpochMilli(),
            pad = pad,
            status = launch.optJSONObject("status")?.optString("abbrev").orEmpty(),
            boosterCatch = catch,
            timeline = timeline(launch),
            splashdownKm = landing?.optDouble("downrange_distance")?.takeIf { !it.isNaN() && it > 0 },
        )
    }
}.getOrNull()

/** "Starship | Starlink Group 31-1 (Starship Flight 14)" -> "Flight 14"; otherwise the mission name. */
private fun shortName(name: String): String =
    Regex("""Flight \d+""").find(name)?.value ?: name.substringAfter("| ").ifBlank { name }

/** Countdown and flight events ("Ignition", "Stage 2 Separation"...) to seconds from liftoff. */
private fun timeline(launch: JSONObject): Map<String, Double> {
    val events = launch.optJSONArray("timeline") ?: return emptyMap()
    return (0 until events.length()).mapNotNull { i ->
        val event = events.optJSONObject(i) ?: return@mapNotNull null
        val name = event.optJSONObject("type")?.optString("abbrev") ?: return@mapNotNull null
        val seconds = runCatching { Duration.parse(event.getString("relative_time")).toMillis() / 1000.0 }.getOrNull()
            ?: return@mapNotNull null
        name to seconds
    }.toMap()
}
