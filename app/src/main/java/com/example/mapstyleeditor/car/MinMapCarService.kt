package com.example.mapstyleeditor.car

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.mapstyleeditor.account.MapboxAccount
import com.example.mapstyleeditor.account.MapboxUsage
import com.example.mapstyleeditor.learn.TripHistory
import com.example.mapstyleeditor.ui.Place
import com.example.mapstyleeditor.voice.VoiceDestinations
import com.mapbox.common.MapboxOptions
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.extension.androidauto.MapboxCarMap
import com.mapbox.maps.extension.androidauto.mapboxMapInstaller
import kotlinx.coroutines.launch

/** What Android Auto connects to: MinMap as a navigation app on the car screen. */
class MinMapCarService : CarAppService() {
    // Debug builds talk to any host (the desktop head unit for testing); release builds only to
    // Android Auto and the other hosts Google lists.
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
        }

    override fun onCreateSession(sessionInfo: SessionInfo): Session = MinMapCarSession()
}

/**
 * One connection to the car. The map needs your Mapbox account and location, both set up on the
 * phone; until they are, the car shows what's missing. "Navigate to ..." from the Assistant (or a
 * geo link) arrives as an intent and starts a drive.
 */
class MinMapCarSession : Session() {
    private val mapboxCarMap: MapboxCarMap = mapboxMapInstaller().install { carContext ->
        MapboxUsage.init(carContext)
        MapboxAccount.load(carContext)
        // Mapbox won't create a map without a token; signed out, the map stays blank behind the
        // "sign in on your phone" message, and signing in on the phone sets the real one.
        if (MapboxAccount.token.isEmpty()) MapboxOptions.accessToken = SIGNED_OUT_TOKEN
        MapInitOptions(carContext)
    }
    private var map: CarMap? = null
    private var navigator: CarNavigator? = null
    private var mapScreen: MapScreen? = null
    /** A drive asked for before the map was ready (signed out, no location yet). */
    private var waiting: Intent? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                navigator?.shutdown()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        MapboxAccount.load(carContext)
        waiting = intent.takeIf { it.action == CarContext.ACTION_NAVIGATE }
        return blockedScreen() ?: mapScreen()
    }

    override fun onNewIntent(intent: Intent) {
        if (intent.action != CarContext.ACTION_NAVIGATE) return
        if (mapScreen == null) waiting = intent else navigateTo(intent)
    }

    // Day/night on the car: the map's light follows it (see carStyleConfig).
    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        map?.applyStyle()
    }

    private fun mapScreen(): MapScreen {
        mapScreen?.let { return it }
        val carMap = CarMap(carContext) { mapScreen?.invalidate() }
        val nav = CarNavigator(carContext, carMap, lifecycleScope) { mapScreen?.invalidate() }
        map = carMap
        navigator = nav
        return MapScreen(carContext, mapboxCarMap, carMap) { nav }.also { screen ->
            mapScreen = screen
            waiting?.let { intent ->
                waiting = null
                navigateTo(intent)
            }
        }
    }

    /** What has to happen on the phone before the map can work, if anything. */
    private fun blockedScreen(): Screen? = when {
        MapboxAccount.token.isEmpty() -> MessageScreen(
            carContext,
            title = "Sign in on your phone",
            message = "Open MinMap on your phone and connect your Mapbox account, then try again.",
            actionTitle = "Try again",
        ) { retry(it) }
        !hasLocation() -> MessageScreen(
            carContext,
            title = "Allow location",
            message = "MinMap needs your location to show where you are and guide you.",
            actionTitle = "Allow",
        ) { screen ->
            carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { _, _ ->
                retry(screen)
            }
        }
        else -> null
    }

    private fun retry(current: Screen) {
        MapboxAccount.load(carContext)
        if (MapboxAccount.token.isNotEmpty()) MapboxOptions.accessToken = MapboxAccount.token
        val next = blockedScreen() ?: mapScreen()
        carContext.getCarService(ScreenManager::class.java).push(next)
        current.finish()
    }

    /** "Navigate to ...": a geo link with coordinates, or a place name to look up. */
    private fun navigateTo(intent: Intent) {
        val nav = navigator ?: return
        val uri = intent.data ?: return
        val (coordinates, query) = parseGeo(uri.schemeSpecificPart.orEmpty())
        if (coordinates != null) {
            nav.start(Place(query ?: "Destination", "", coordinates.second, coordinates.first))
            return
        }
        if (query.isNullOrBlank()) return
        lifecycleScope.launch {
            val history = TripHistory(carContext)
            val here = map?.here
            val place = VoiceDestinations(MapboxAccount.token) { history.all() }.find(query, here, here)
            if (place == null) {
                CarToast.makeText(carContext, "Couldn't find “$query”", CarToast.LENGTH_LONG).show()
            } else {
                nav.start(place)
            }
        }
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val SIGNED_OUT_TOKEN = "pk.signed-out"
    }
}

/**
 * "geo:37.42,-122.08", "geo:0,0?q=Starbucks" or "geo:37.42,-122.08?q=Home": coordinates as
 * (latitude, longitude) unless they're 0,0 (meaning "look it up"), and the query if any.
 */
fun parseGeo(part: String): Pair<Pair<Double, Double>?, String?> {
    val query = part.substringAfter("q=", "").substringBefore('&').takeIf { it.isNotBlank() }?.trim()
    val numbers = part.substringBefore('?').split(',').mapNotNull { it.trim().toDoubleOrNull() }
    val coordinates = numbers.takeIf { it.size >= 2 && !(it[0] == 0.0 && it[1] == 0.0) }?.let { it[0] to it[1] }
    // A query that is itself "lat,lng" (some apps send that) counts as coordinates.
    val queryCoordinates = query?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    return (coordinates ?: queryCoordinates) to query.takeIf { queryCoordinates == null }
}
