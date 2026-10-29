package com.example.mapstyleeditor.car

import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.core.graphics.drawable.IconCompat
import com.example.mapstyleeditor.account.MapboxAccount
import com.example.mapstyleeditor.learn.Trip
import com.example.mapstyleeditor.learn.TripHistory
import com.example.mapstyleeditor.learn.simplifyPath
import com.example.mapstyleeditor.nav.DriveProgress
import com.example.mapstyleeditor.nav.DriveSession
import com.example.mapstyleeditor.nav.GuidanceVoice
import com.example.mapstyleeditor.nav.distanceMeters
import com.example.mapstyleeditor.nav.maneuverArrow
import com.example.mapstyleeditor.nav.planOwnDrive
import com.example.mapstyleeditor.ui.Place
import com.mapbox.geojson.Point
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.TimeZone
import androidx.car.app.navigation.model.Trip as CarTrip

/**
 * A drive on the car screen. Google Maps can't guide underneath another app on Android Auto, so
 * MinMap guides by itself (as in the aosp build): one route request, spoken turns through the car's
 * speakers, rerouting and arrival. Android Auto is told navigation has started, so other navigation
 * apps stand down, and the driver's cluster (where the car has one) gets the next turn.
 */
class CarNavigator(
    private val carContext: CarContext,
    private val map: CarMap,
    private val scope: CoroutineScope,
    /** Something changed that the screen shows. */
    private val onChanged: () -> Unit,
) {
    var drive: DriveSession? = null
        private set
    var destination: Place? = null
        private set
    /** Planning the route (before the first turn is known). */
    var starting = false
        private set

    private val history = TripHistory(carContext)
    private val voice = GuidanceVoice(carContext)
    private val arrows = HashMap<String, CarIcon>()
    private var jobs = emptyList<Job>()
    private val navigation = carContext.getCarService(NavigationManager::class.java).apply {
        setNavigationManagerCallback(object : NavigationManagerCallback {
            // Android Auto ending our navigation (another app started navigating, or the car's own button).
            override fun onStopNavigation() = end()
        })
    }

    fun start(place: Place) {
        end()
        destination = place
        starting = true
        onChanged()
        navigation.navigationStarted()
        val job = scope.launch {
            val token = MapboxAccount.token
            val target = Point.fromLngLat(place.longitude, place.latitude)
            // A moment for a first position if the car screen has only just opened.
            var here = map.here
            for (i in 0 until 20) {
                if (here != null) break
                delay(250)
                here = map.here
            }
            val plan = planOwnDrive(token, history.all(), here, map.bearing, target)
            val startedAt = System.currentTimeMillis()
            history.record(Trip(name = place.name, destination = target, startedAt = startedAt, origin = here))
            val session = DriveSession(
                token = token,
                destination = target,
                startedAt = startedAt,
                vias = plan.vias,
                location = { map.here },
                bearing = { map.bearing },
                ownGuidance = true,
                initialRoute = plan.route,
                speak = voice::speak,
            )
            drive = session
            starting = false
            map.showDrive(session.route, driving = true)
            onChanged()
            jobs = jobs + launch { session.run() } + launch { watch(session) } + launch { recordPath(session) }
        }
        jobs = listOf(job)
    }

    /** Ends the drive (End button, arrival, or Android Auto asking). */
    fun end() {
        val wasActive = drive != null || starting
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        drive = null
        destination = null
        starting = false
        map.showDrive(null, driving = false)
        if (wasActive) navigation.navigationEnded()
        onChanged()
    }

    fun shutdown() {
        end()
        navigation.clearNavigationManagerCallback()
        voice.shutdown()
    }

    /** The next turn for the template, the cluster and the map; ends the drive a little after arriving. */
    private suspend fun watch(session: DriveSession) {
        var shownRoute = session.route
        var shown: DriveProgress? = null
        while (true) {
            if (session.route !== shownRoute) {
                shownRoute = session.route
                map.showDrive(shownRoute, driving = true)
            }
            val progress = session.progress
            if (progress != shown) {
                shown = progress
                progress?.let { updateCluster(it) }
                onChanged()
            }
            if (session.arrived) {
                onChanged()
                delay(ARRIVED_LINGER_MS)
                end()
                return
            }
            delay(1_000)
        }
    }

    /** The roads actually driven (a point every ~25 m), saved to the trip; it's how your usual way is learned. */
    private suspend fun recordPath(session: DriveSession) {
        val path = mutableListOf<Point>()
        try {
            while (true) {
                map.here?.let { p -> if (path.isEmpty() || distanceMeters(path.last(), p) > 25) path += p }
                delay(3_000)
            }
        } finally {
            if (path.size >= 5) history.attachPath(session.startedAt, simplifyPath(path))
        }
    }

    /** The car template's step for [progress]: maneuver (with its arrow), instruction and road. */
    fun step(progress: DriveProgress): Step {
        val next = progress.next
        val type = carManeuverType(next)
        val maneuver = Maneuver.Builder(type).setIcon(arrow(next.type, next.modifier))
        if (needsExitNumber(type)) maneuver.setRoundaboutExitNumber(next.exit ?: 1)
        return Step.Builder(next.instruction.ifBlank { next.names.firstOrNull().orEmpty() })
            .setManeuver(maneuver.build())
            .apply { next.names.firstOrNull()?.let(::setRoad) }
            .build()
    }

    /** Time and distance left to the destination. */
    fun estimate(progress: DriveProgress): TravelEstimate {
        val seconds = progress.secondsLeft ?: 0.0
        val arrival = DateTimeWithZone.create(System.currentTimeMillis() + (seconds * 1000).toLong(), TimeZone.getDefault())
        return TravelEstimate.Builder(carDistance(progress.metersLeft), arrival)
            .setRemainingTimeSeconds(seconds.toLong())
            .build()
    }

    private fun updateCluster(progress: DriveProgress) {
        val place = destination ?: return
        runCatching {
            navigation.updateTrip(
                CarTrip.Builder()
                    .addStep(step(progress), estimate(progress))
                    .addDestination(Destination.Builder().setName(place.name).setAddress(place.detail).build(), estimate(progress))
                    .apply { progress.next.names.firstOrNull()?.let(::setCurrentRoad) }
                    .build(),
            )
        }
    }

    private fun arrow(type: String, modifier: String?): CarIcon = arrows.getOrPut("$type|$modifier") {
        CarIcon.Builder(IconCompat.createWithBitmap(maneuverArrow(type, modifier))).build()
    }

    private companion object {
        const val ARRIVED_LINGER_MS = 8_000L
    }
}
