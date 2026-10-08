package com.example.mapstyleeditor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.mapstyleeditor.learn.Suggestion
import com.example.mapstyleeditor.learn.Trip
import com.example.mapstyleeditor.learn.TripHistory
import com.example.mapstyleeditor.learn.simplifyPath
import com.example.mapstyleeditor.learn.suggestDestinations
import com.example.mapstyleeditor.learn.usualWayVias
import com.example.mapstyleeditor.nav.distanceMeters
import com.example.mapstyleeditor.nav.fetchDriveMinutes
import com.example.mapstyleeditor.nav.fetchRoute
import com.example.mapstyleeditor.robotaxi.RobotaxiAreas
import com.example.mapstyleeditor.robotaxi.RobotaxiProvider
import com.example.mapstyleeditor.robotaxi.providersCovering
import com.example.mapstyleeditor.nav.DriveNotification
import com.example.mapstyleeditor.nav.DriveSession
import com.example.mapstyleeditor.nav.GoogleNav
import com.example.mapstyleeditor.nav.ROUTE_LAYER_IDS
import com.example.mapstyleeditor.nav.ReturnToApp
import com.example.mapstyleeditor.nav.Route
import com.example.mapstyleeditor.nav.RouteSnappingProvider
import com.example.mapstyleeditor.nav.RouteTrimmer
import com.mapbox.maps.plugin.locationcomponent.LocationProvider
import com.example.mapstyleeditor.ui.DriveOverlay
import com.example.mapstyleeditor.places.TappedPoi
import com.example.mapstyleeditor.account.MapboxAccount
import com.example.mapstyleeditor.demo.DemoDrive
import com.example.mapstyleeditor.update.AppUpdates
import android.content.pm.ApplicationInfo
import com.example.mapstyleeditor.starbase.Pad
import com.example.mapstyleeditor.starbase.STARBASE
import com.example.mapstyleeditor.starbase.StarbaseLaunches
import com.example.mapstyleeditor.starbase.StarbaseLayers
import com.example.mapstyleeditor.starbase.StarbasePill
import com.example.mapstyleeditor.starbase.replayDone
import com.example.mapstyleeditor.starbase.replayOf
import com.example.mapstyleeditor.ui.glassStyle
import com.example.mapstyleeditor.starbase.StarshipLaunch
import com.example.mapstyleeditor.starbase.demoStarbaseLaunch
import com.example.mapstyleeditor.ui.SignInScreen
import com.example.mapstyleeditor.ui.LocationSearchBar
import com.example.mapstyleeditor.ui.Place
import com.example.mapstyleeditor.ui.PoiCard
import com.example.mapstyleeditor.ui.StyleMenu
import com.example.mapstyleeditor.ui.SuggestedDrive
import java.util.Calendar
import com.mapbox.bindgen.Value
import com.mapbox.geojson.Feature
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraBoundsOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.compose.style.layers.generated.FillLayer
import com.mapbox.maps.extension.compose.style.layers.generated.FillLayerState
import com.mapbox.maps.extension.compose.style.layers.generated.LineLayer
import com.mapbox.maps.extension.compose.style.layers.generated.LineCapValue
import com.mapbox.maps.extension.compose.style.layers.generated.LineJoinValue
import com.mapbox.maps.extension.compose.style.layers.generated.LineLayerState
import com.mapbox.maps.extension.compose.style.sources.GeoJSONData
import com.mapbox.maps.extension.compose.style.sources.generated.rememberGeoJsonSourceState
import com.mapbox.maps.extension.compose.style.standard.StandardStyleState
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.viewport.ViewportStatus
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateBearing
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.mapbox.maps.extension.compose.ComposeMapInitOptions
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.MapViewportState
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.style.BooleanValue
import com.mapbox.maps.extension.compose.style.ColorValue
import com.mapbox.maps.extension.compose.style.DoubleValue
import com.mapbox.maps.extension.compose.style.standard.ColorModePointOfInterestLabelsValue
import com.mapbox.maps.extension.compose.style.standard.LightPresetValue
import com.mapbox.maps.extension.compose.style.standard.MapboxStandardStyle
import com.mapbox.maps.extension.compose.style.standard.StandardStyleConfigurationState
import com.mapbox.maps.extension.compose.style.standard.ThemeValue
import com.mapbox.maps.extension.compose.style.standard.rememberStandardStyleState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Mapbox throws if a map is created without a token, so the map only shows once an account
        // is signed in (see SignInScreen).
        MapboxAccount.load(this)
        val editor = EditorState(getSharedPreferences("map_style", MODE_PRIVATE))
        val demo = DemoDrive.from(intent, applicationInfo)
        // Debug builds can try the updater against a test release listing.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            intent?.getStringExtra("update_feed")?.let { AppUpdates.feedUrl = it }
        }
        AppUpdates.justUpdatedTo(this)?.let { Toast.makeText(this, "MinMap updated to $it", Toast.LENGTH_LONG).show() }
        val starbaseDemo = demoStarbaseLaunch(intent, applicationInfo)
        setContent {
            AppTheme {
                MapStyleEditorScreen(editor, demo, starbaseDemo)
            }
        }
    }
}

/** Holds the current style and saves every change, so edits survive closing the app. */
class EditorState(private val prefs: SharedPreferences) {
    var style by mutableStateOf(MapStyle.fromConfigJsonOrDefault(prefs.getString(KEY_STYLE, null)))
        private set

    fun update(transform: (MapStyle) -> MapStyle) {
        style = transform(style)
        prefs.edit { putString(KEY_STYLE, style.toConfigJson()) }
    }

    /** Robotaxi service areas aren't part of Mapbox's style config, so they're saved on their own. */
    var showRobotaxi by mutableStateOf(prefs.getBoolean(KEY_ROBOTAXI, true))
        private set

    fun toggleRobotaxi(show: Boolean) {
        showRobotaxi = show
        prefs.edit { putBoolean(KEY_ROBOTAXI, show) }
    }

    fun applyPreset(preset: Preset) = update { preset.style }

    /** Replaces the style with pasted config JSON. Call [validateConfigJson] first. */
    fun import(json: String) = update { MapStyle.fromConfigJson(json) }

    private companion object {
        const val KEY_STYLE = "mapbox_config"
        const val KEY_ROBOTAXI = "show_robotaxi_areas"
    }
}

@Composable
fun MapStyleEditorScreen(editor: EditorState, demo: DemoDrive? = null, starbaseDemo: StarshipLaunch? = null) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val live = remember { LiveLocation() }
    var drive by remember { mutableStateOf<DriveSession?>(null) }
    val startDrive = rememberDriveStarter(live) { drive = it }
    // Screenshot drive (debug builds only, see DemoDrive): drive mode straight away, without Google Maps.
    LaunchedEffect(demo) {
        if (demo == null) return@LaunchedEffect
        while (live.point == null) delay(250)
        drive = DriveSession(
            token = MapboxAccount.token,
            destination = demo.destination,
            startedAt = System.currentTimeMillis(),
            vias = emptyList(),
            location = { live.point },
            bearing = { live.bearing },
        )
        GoogleNav.update(demo.instruction)
    }

    // The app opens on your location, so it asks for location up front (once; "Don't allow" just
    // leaves it starting on the default view). Re-checked on resume in case it's granted later.
    val context = LocalContext.current
    var hasLocation by remember { mutableStateOf(hasLocationPermission(context)) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = hasLocationPermission(context)
    }
    LaunchedEffect(Unit) {
        if (!hasLocation) askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasLocation = hasLocationPermission(context) }
    // Automatic update checks (and installs, if switched on) when the app comes to the front.
    val updateScope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updateScope.launch { AppUpdates.onAppResumed(context) } }

    Surface(color = MaterialTheme.colorScheme.background) {
        // Edge to edge: the map runs under the status and navigation bars; the controls on top of it
        // inset themselves instead.
        Box(Modifier.fillMaxSize()) {
            if (MapboxAccount.token.isNotEmpty()) {
                MapWithSearch(
                    editor = editor,
                    drive = drive,
                    live = live,
                    hasLocation = hasLocation,
                    menuOpen = menuOpen,
                    onMenuOpenChange = { menuOpen = it },
                    onDrive = {
                        menuOpen = false
                        startDrive(it)
                    },
                    onEndDrive = { drive = null },
                    starbaseDemo = starbaseDemo,
                )
            } else {
                SignInScreen()
            }
        }
    }
}

private fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** The phone's most recent known position from any location source, without waiting for a new fix. */
@SuppressLint("MissingPermission") // Checked first.
private fun lastKnownPoint(context: Context): Point? {
    if (!hasLocationPermission(context)) return null
    val manager = context.getSystemService(LocationManager::class.java)
    return manager.getProviders(true)
        .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
        ?.let { Point.fromLngLat(it.longitude, it.latitude) }
}

/** Latest puck position and travel direction, written by the map and read by the drive session. */
class LiveLocation {
    @Volatile var point: Point? = null
    @Volatile var bearing: Double? = null
}

/**
 * Returns the "Drive" action: make sure we have location and notification access, start Google
 * Maps navigating to the place, then bring this app back on top in drive mode while Google Maps
 * keeps guiding (and speaking) in the background.
 */
@Composable
private fun rememberDriveStarter(live: LiveLocation, onStarted: (DriveSession) -> Unit): (Place) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Place?>(null) }
    var askAccess by remember { mutableStateOf(false) }
    var askOverlay by remember { mutableStateOf(false) }
    var pendingAfterNotifications by remember { mutableStateOf<Place?>(null) }
    val scope = rememberCoroutineScope()

    /**
     * Works out whether you have a usual way to [place] (a quick look at the fastest route, compared
     * with your past drives there), then starts Google Maps along it.
     */
    suspend fun launchDrive(place: Place) {
        val token = MapboxAccount.token
        val destination = Point.fromLngLat(place.longitude, place.latitude)
        val here = live.point ?: lastKnownPoint(context)
        val history = TripHistory(context)
        val vias = if (here == null) emptyList() else withTimeoutOrNull(4_000) {
            fetchRoute(token, listOf(here, destination))?.let { fastest ->
                usualWayVias(history.all(), here, destination, fastest)
            }
        }.orEmpty()

        // Plain navigation, or a directions link with your usual way as pass-through stops (tested:
        // Google Maps on Android keeps link waypoints when it starts navigating).
        val uri = if (vias.isEmpty()) {
            "google.navigation:q=${place.latitude},${place.longitude}"
        } else {
            "https://www.google.com/maps/dir/?api=1&destination=${place.latitude},${place.longitude}" +
                "&waypoints=" + vias.joinToString("%7C") { "${it.latitude()},${it.longitude()}" } +
                "&travelmode=driving&dir_action=navigate"
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(GoogleNav.MAPS_PACKAGE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Drive mode needs the Google Maps app.", Toast.LENGTH_LONG).show()
            return
        }
        if (vias.isNotEmpty()) {
            Toast.makeText(context, "Taking your usual way to ${place.name}", Toast.LENGTH_SHORT).show()
        }

        // Learn from it: where, when, and from where (the roads driven are added when it ends).
        val startedAt = System.currentTimeMillis()
        history.record(Trip(name = place.name, destination = destination, startedAt = startedAt, origin = here))
        onStarted(
            DriveSession(
                token = token,
                destination = destination,
                startedAt = startedAt,
                vias = vias,
                location = { live.point },
                bearing = { live.bearing },
            ),
        )
        // Google Maps only starts guiding while it's on screen, so it stays in front until its first
        // instruction arrives; then ReturnToApp brings us back (see there for why it needs an overlay).
        ReturnToApp.arm()
    }

    fun start(place: Place) {
        val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasLocation) {
            pending = place
            return
        }
        if (!GoogleNav.hasNotificationAccess(context)) {
            pending = place
            askAccess = true
            return
        }
        if (!ReturnToApp.canReturn(context)) {
            pending = place
            askOverlay = true
            return
        }
        // Optional: the status-bar turn chip. Asked once; the drive goes ahead either way.
        val prefs = context.getSharedPreferences("notices", Context.MODE_PRIVATE)
        val notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!notificationsAllowed && !prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) {
            prefs.edit { putBoolean(KEY_ASKED_NOTIFICATIONS, true) }
            pendingAfterNotifications = place
            return
        }
        pending = null
        scope.launch { launchDrive(place) }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val place = pending
        if (grants.values.any { it } && place != null) start(place) else pending = null
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingAfterNotifications?.let { place ->
            pendingAfterNotifications = null
            start(place)
        }
    }
    LaunchedEffect(pendingAfterNotifications) {
        if (pendingAfterNotifications != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(pending, askAccess) {
        val needsLocation = pending != null && ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) != PackageManager.PERMISSION_GRANTED
        if (needsLocation) {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    if (askAccess) {
        AlertDialog(
            onDismissRequest = { askAccess = false; pending = null },
            title = { Text("Read Google Maps directions") },
            text = {
                Text(
                    "To show Google Maps' turns on your map, turn on notification access for MinMap. " +
                        "Android grants it for all notifications, but the app only reads Google Maps' " +
                        "navigation notification. Then tap Drive again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askAccess = false
                    pending = null
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            GoogleNav.listenerComponent(context).flattenToString(),
                        ),
                    )
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { askAccess = false; pending = null }) { Text("Not now") } },
        )
    }

    if (askOverlay) {
        AlertDialog(
            onDismissRequest = { askOverlay = false; pending = null },
            title = { Text("Come back from Google Maps") },
            text = {
                Text(
                    "Google Maps has to be on screen to start guiding. To jump back to your map once it has, " +
                        "Android needs \"Display over other apps\" for MinMap: it shows a small " +
                        "\"Back to your map\" bubble for a moment. Turn it on, then tap Drive again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askOverlay = false
                    pending = null
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                    )
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { askOverlay = false; pending = null }) { Text("Not now") } },
        )
    }
    return ::start
}

@Composable
private fun MapWithSearch(
    editor: EditorState,
    drive: DriveSession?,
    live: LiveLocation,
    hasLocation: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onDrive: (Place) -> Unit,
    onEndDrive: () -> Unit,
    starbaseDemo: StarshipLaunch? = null,
) {
    val style = editor.style
    val hazeState = rememberHazeState()
    val darkMap = style.light == LightPreset.NIGHT || style.light == LightPreset.DUSK
    val context = LocalContext.current
    // Open where you are: the phone's last known position if it has one (instant, no waiting for GPS).
    val startPoint = remember { lastKnownPoint(context) }
    val viewport = rememberMapViewportState {
        setCameraOptions {
            // Far enough out that the map is streets, water and parks rather than one tower's facade.
            center(startPoint ?: Point.fromLngLat(-73.9855, 40.758))
            zoom(14.2)
            pitch(45.0)
            bearing(-20.0)
        }
    }
    // A demo launch (debug builds, see demoStarbaseLaunch) opens looking at the pad.
    LaunchedEffect(starbaseDemo) {
        val pad = starbaseDemo?.pad ?: return@LaunchedEffect
        viewport.setCameraOptions(starbaseCamera(pad))
    }
    // Starbase: its 3D pads (and rocket, around a launch) while the map is anywhere near it. Close
    // in, Standard's own buildings there are switched off: it draws each launch tower as a plain
    // 143 m block that would swallow the tower model.
    val showStarbase by remember {
        derivedStateOf { viewport.cameraState?.let { it.zoom >= 9 && distanceMeters(it.center, STARBASE) < 60_000 } == true }
    }
    val atStarbase by remember {
        derivedStateOf { viewport.cameraState?.let { it.zoom >= 13 && distanceMeters(it.center, STARBASE) < 4_000 } == true }
    }
    val launches = remember { StarbaseLaunches(context) }
    var liveLaunch by remember { mutableStateOf<StarshipLaunch?>(null) }
    var lastFlight by remember { mutableStateOf<StarshipLaunch?>(null) }
    var replay by remember { mutableStateOf<StarshipLaunch?>(null) }
    LaunchedEffect(showStarbase) {
        if (!showStarbase || starbaseDemo?.status == "Go") return@LaunchedEffect
        while (true) {
            // Both only ask Launch Library when their cached answers are due (see there).
            liveLaunch = launches.current()
            lastFlight = launches.lastFlight()
            delay(60_000)
        }
    }
    LaunchedEffect(replay) {
        val flight = replay ?: return@LaunchedEffect
        // Watch from beside the pad it flew from, then let the scene go back to the real one.
        viewport.flyTo(starbaseCamera(flight.pad))
        while (!replayDone(flight)) delay(1_000)
        replay = null
    }
    val starshipLaunch = starbaseDemo?.takeIf { it.status == "Go" } ?: replay ?: liveLaunch
    // No last known position (fresh install, or location just granted): move to the first fix instead.
    LaunchedEffect(hasLocation) {
        if (startPoint != null || !hasLocation) return@LaunchedEffect
        val first = withTimeoutOrNull(20_000) {
            while (live.point == null) delay(250)
            live.point
        } ?: return@LaunchedEffect
        viewport.easeTo(cameraOptions { center(first); zoom(14.2) })
    }
    // Learned destinations. Worked out when the map opens, when you come back to the app (at most
    // every 10 minutes, since each one costs a drive-time request), after a drive, and after a
    // "forget". They only ever offer a drive; nothing starts on its own.
    val history = remember { TripHistory(context) }
    var suggestions by remember { mutableStateOf<List<SuggestedDrive>>(emptyList()) }
    var suggestionsKey by remember { mutableIntStateOf(0) }
    var suggestedAt by remember { mutableLongStateOf(0L) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (System.currentTimeMillis() - suggestedAt > SUGGEST_REFRESH_MS) suggestionsKey++
    }
    LaunchedEffect(drive == null, suggestionsKey) {
        if (drive != null) return@LaunchedEffect
        suggestedAt = System.currentTimeMillis()
        // Give the location dot a moment, so "from near here" and drive times can count.
        val here = withTimeoutOrNull(5_000) {
            while (live.point == null) delay(250)
            live.point
        } ?: startPoint
        val picks = suggestDestinations(history.all(), Calendar.getInstance(), here)
        fun place(s: Suggestion) = Place(s.name, "", s.point.longitude(), s.point.latitude())
        suggestions = picks.map { SuggestedDrive(place(it), null) }
        if (here != null && picks.isNotEmpty()) {
            suggestions = picks.map { SuggestedDrive(place(it), fetchDriveMinutes(MapboxAccount.token, here, it.point)) }
        }
    }
    var robotaxiAreas by remember { mutableStateOf<Map<RobotaxiProvider, List<Feature>>>(emptyMap()) }
    LaunchedEffect(Unit) { robotaxiAreas = RobotaxiAreas.load(context) }
    val showRobotaxi = editor.showRobotaxi && robotaxiAreas.isNotEmpty()
    val navInstruction by GoogleNav.current.collectAsState()
    // Google's guidance as shown in the turn card and status bar, except that while usual-way stops
    // lie ahead its arrival time (which only covers the next stop) becomes the whole trip's.
    val shownInstruction = navInstruction?.let { nav ->
        drive?.tripArrival?.let { nav.copy(arrival = it) } ?: nav
    }
    // Stop following the (soon hidden) puck so the camera stays put where the drive ended.
    val endDrive = {
        viewport.idle()
        onEndDrive()
    }

    var tappedPoi by remember { mutableStateOf<TappedPoi?>(null) }
    val view = LocalView.current

    // The status bar now sits on the map, so its icons follow the map: light icons on dark maps.
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkMap
        }
    }

    // Camera while driving. Both views follow the puck heading-up; "close-up" is the low chase view:
    // nearly street level just behind the puck, looking down the road at the 3D city ahead.
    var closeUp by rememberSaveable { mutableStateOf(false) }
    var followKey by remember { mutableIntStateOf(0) }
    val status = viewport.mapViewportStatus
    val following = status is ViewportStatus.State || status is ViewportStatus.Transition

    if (drive != null) {
        val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val density = LocalDensity.current
        val screenHeight = LocalWindowInfo.current.containerSize.height
        // Puck low on screen (clear of the turn card) so more of the road ahead shows. In close-up
        // it sits just below the middle, centred, with the camera tight behind it.
        val standardPadding = with(density) { (220.dp + statusBar).toPx().toDouble() }
        val closeUpPadding = screenHeight * 0.2
        LaunchedEffect(drive, closeUp, followKey) {
            delay(150) // Lets the camera limits for this view apply first (see StyledMap).
            viewport.transitionToFollowPuckState(
                FollowPuckViewportStateOptions.Builder()
                    .zoom(if (closeUp) CLOSE_UP_ZOOM else 16.5)
                    .pitch(if (closeUp) CLOSE_UP_PITCH else 60.0)
                    .bearing(FollowPuckViewportStateBearing.SyncWithLocationPuck)
                    .padding(EdgeInsets(if (closeUp) closeUpPadding else standardPadding, 0.0, 0.0, 0.0))
                    .build(),
            )
        }
        // Moving the map by hand (or anything else) stops Mapbox following. Pick it back up on its
        // own: quickly if following never started, after a few seconds if you panned on purpose.
        LaunchedEffect(drive, status) {
            if (following) return@LaunchedEffect
            delay(if (status == null) 1_500 else REFOLLOW_AFTER_MS)
            followKey++
        }
        LaunchedEffect(drive) { drive.run() }
        // Record the roads you actually drive (a point every ~25 m), saved to this trip when the
        // drive ends; that's what "your usual way" is learned from.
        LaunchedEffect(drive) {
            val path = mutableListOf<Point>()
            try {
                while (true) {
                    live.point?.let { p -> if (path.isEmpty() || distanceMeters(path.last(), p) > 25) path += p }
                    delay(3_000)
                }
            } finally {
                if (path.size >= 5) history.attachPath(drive.startedAt, simplifyPath(path))
            }
        }
        // When Google Maps' guidance goes away for good (arrived, or ended there), end ours too.
        LaunchedEffect(drive) {
            var lastSeen = 0L
            while (true) {
                if (GoogleNav.current.value != null) lastSeen = System.currentTimeMillis()
                if (lastSeen > 0 && System.currentTimeMillis() - lastSeen > GUIDANCE_GONE_MS) endDrive()
                delay(1_000)
            }
        }
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
        // Our own next turn in the status bar / Now Bar, kept in step with Google Maps' guidance.
        LaunchedEffect(drive, shownInstruction) { DriveNotification.show(context, shownInstruction) }
        DisposableEffect(drive) { onDispose { DriveNotification.cancel(context) } }
        PictureInPictureNotice(guidanceStarted = navInstruction != null)
    }

    Box(Modifier.fillMaxSize()) {
        StyledMap(
            style, viewport, drive, live,
            hasLocation = hasLocation,
            closeUp = drive != null && closeUp,
            robotaxiAreas = if (showRobotaxi) robotaxiAreas else emptyMap(),
            starbase = StarbaseView(show = showStarbase, hideBuildings = atStarbase, launch = starshipLaunch),
            onPoiClick = { if (drive == null) tappedPoi = it },
            onMapClick = { tappedPoi = null },
            modifier = Modifier.fillMaxSize().hazeSource(hazeState),
        )
        val bars = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
        if (showRobotaxi) {
            // The dataset's licence (CC BY 4.0) asks for this credit wherever the areas are shown.
            Text(
                "Service areas: Robotaxi Tracker",
                color = if (darkMap) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.6f),
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .then(bars)
                    .padding(end = 10.dp, bottom = 10.dp)
                    .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://robotaxitracker.com"))) },
            )
        }
        if (drive != null) {
            Box(bars) {
                DriveOverlay(
                    hazeState = hazeState,
                    darkMap = darkMap,
                    instruction = shownInstruction,
                    closeUp = closeUp,
                    following = following,
                    onToggleView = { closeUp = !closeUp },
                    onRecenter = { followKey++ },
                    onEnd = {
                        // Ending here ends Google Maps' navigation too, using its own "Exit navigation" button.
                        runCatching { navInstruction?.exitAction?.send() }
                        endDrive()
                    },
                )
            }
        } else if (tappedPoi != null) {
            PoiCard(
                poi = tappedPoi!!,
                hazeState = hazeState,
                darkMap = darkMap,
                accessToken = MapboxAccount.token,
                robotaxi = providersCovering(robotaxiAreas, tappedPoi!!.point),
                onDrive = {
                    tappedPoi = null
                    onDrive(it)
                },
                onClose = { tappedPoi = null },
                modifier = Modifier.align(Alignment.BottomCenter).then(bars).padding(start = 12.dp, end = 12.dp, bottom = 36.dp),
            )
        } else {
            LocationSearchBar(
                hazeState = hazeState,
                accessToken = MapboxAccount.token,
                darkMap = darkMap,
                onPlace = { place ->
                    viewport.flyTo(cameraOptions {
                        center(Point.fromLngLat(place.longitude, place.latitude))
                        zoom(15.0)
                        pitch(45.0)
                    })
                },
                onDrive = onDrive,
                robotaxiAt = { providersCovering(robotaxiAreas, Point.fromLngLat(it.longitude, it.latitude)) },
                suggestions = suggestions,
                onForgetSuggestion = { place ->
                    history.forget(Point.fromLngLat(place.longitude, place.latitude))
                    Toast.makeText(context, "MinMap won't suggest ${place.name} anymore", Toast.LENGTH_SHORT).show()
                    suggestionsKey++
                },
                near = { viewport.cameraState?.center ?: live.point },
                here = { live.point },
                // Lifted clear of the Mapbox logo and attribution button along the bottom edge.
                modifier = Modifier.align(Alignment.BottomCenter).then(bars).padding(bottom = 40.dp),
            )
        }
        if (drive == null && atStarbase) {
            StarbasePill(
                hazeState = hazeState,
                glass = remember(darkMap) { glassStyle(darkMap) },
                darkMap = darkMap,
                live = liveLaunch,
                replay = replay,
                lastFlight = lastFlight,
                onReplay = { lastFlight?.let { replay = replayOf(it) } },
                onEndReplay = { replay = null },
                // Kept clear of the style menu's button in the top-right corner, and centred.
                modifier = Modifier.align(Alignment.TopCenter).then(bars).padding(top = 14.dp, start = 72.dp, end = 72.dp),
            )
        }
        if (drive == null) {
            // Last, so the menu and its button sit above everything else on the map.
            StyleMenu(editor, hazeState, open = menuOpen, onOpenChange = onMenuOpenChange, modifier = bars)
        }
    }
}

private const val GUIDANCE_GONE_MS = 10_000L
/** After you move the map mid-drive, the camera goes back to following you after this long. */
private const val REFOLLOW_AFTER_MS = 8_000L
private const val CLOSE_UP_ZOOM = 19.4
private const val CLOSE_UP_PITCH = 78.0
private const val SUGGEST_REFRESH_MS = 10 * 60 * 1000L

/**
 * One-time heads-up on the first drive: Google Maps shrinks into a floating picture-in-picture
 * window when we come back over it, and only the user can switch that off (per app, in Android
 * settings). Shown once guidance has started, i.e. once we're back on the map.
 */
@Composable
private fun PictureInPictureNotice(guidanceStarted: Boolean) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("notices", Context.MODE_PRIVATE) }
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(guidanceStarted) {
        if (guidanceStarted && !prefs.getBoolean(KEY_PIP_NOTICE, false)) {
            prefs.edit { putBoolean(KEY_PIP_NOTICE, true) }
            show = true
        }
    }
    if (!show) return

    AlertDialog(
        onDismissRequest = { show = false },
        title = { Text("Hide Google Maps' mini-map?") },
        text = {
            Text(
                "While it navigates, Google Maps floats a small picture-in-picture window over your map. " +
                    "Turn picture-in-picture off for Google Maps and it guides from the background instead: " +
                    "voice directions and the turn card keep working. Other apps aren't affected. " +
                    "You won't see this message again.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                show = false
                val pip = Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", Uri.parse("package:${GoogleNav.MAPS_PACKAGE}"))
                try {
                    context.startActivity(pip)
                } catch (_: ActivityNotFoundException) {
                    // Phones without the direct page: Google Maps' app info, where picture-in-picture lives.
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${GoogleNav.MAPS_PACKAGE}")),
                    )
                }
            }) { Text("Open settings") }
        },
        dismissButton = { TextButton(onClick = { show = false }) { Text("Leave it on") } },
    )
}

private const val KEY_PIP_NOTICE = "maps_pip_notice_shown"
private const val KEY_ASKED_NOTIFICATIONS = "asked_notification_permission"

@Composable
private fun StyledMap(
    style: MapStyle,
    viewport: MapViewportState,
    drive: DriveSession?,
    live: LiveLocation,
    hasLocation: Boolean,
    closeUp: Boolean,
    robotaxiAreas: Map<RobotaxiProvider, List<Feature>>,
    starbase: StarbaseView,
    onPoiClick: (TappedPoi) -> Unit,
    onMapClick: () -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current.density
    // A TextureView draws inside the normal view hierarchy, so the search bar's glass can blur it.
    // The default SurfaceView sits in its own window layer and would show through as a flat tint.
    val initOptions = remember { ComposeMapInitOptions(density, textureView = true) }
    val currentOnPoiClick by rememberUpdatedState(onPoiClick)
    val currentOnMapClick by rememberUpdatedState(onMapClick)
    // Set up once (rememberStandardStyleState's init block re-runs on every recomposition, which
    // would register the tap handlers again each time).
    val standardStyle = remember {
        StandardStyleState().apply {
            interactionsState
                .onPoiClicked { poi, _ ->
                    val point = poi.geometry as? Point ?: return@onPoiClicked false
                    val name = poi.name ?: return@onPoiClicked false
                    currentOnPoiClick(TappedPoi(name, poi.group, point))
                    true
                }
                // The round 3D-landmark badges (Empire State Building, Chrysler...) are their own set.
                .onLandmarkIconsClicked { landmark, _ ->
                    val point = landmark.geometry as? Point ?: return@onLandmarkIconsClicked false
                    val name = landmark.name ?: return@onLandmarkIconsClicked false
                    currentOnPoiClick(TappedPoi(name, "landmark", point))
                    true
                }
                // Only reached when the tap wasn't on a POI or landmark: closes the POI card.
                .onMapClicked {
                    currentOnMapClick()
                    false
                }
        }
    }
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Keeps the driven part of the route line erased, frame by frame (see RouteTrimmer).
    val trimmer = remember { RouteTrimmer() }
    // Mapbox's own GPS location provider, kept so drive mode can wrap it and hand it back after.
    var gpsProvider by remember { mutableStateOf<LocationProvider?>(null) }
    val route = drive?.route
    LaunchedEffect(route) { trimmer.route = route }
    // Standard's own colours, read from the loaded style. Needed to undo a colour: the config
    // won't take null as "back to default" for colours (it renders them black).
    var defaultColors by remember { mutableStateOf<Map<String, Value>?>(null) }
    // Mapbox's own style state applies config changes to the map whenever these values change.
    LaunchedEffect(style, defaultColors, starbase.hideBuildings) {
        standardStyle.configurationsState.applyStyle(style, defaultColors.orEmpty(), hideBuildings = starbase.hideBuildings)
    }

    MapboxMap(
        modifier,
        composeMapInitOptions = initOptions,
        // No compass or scale bar: clutter on a map you style for looks, and in the way of the turn card.
        compass = {},
        scaleBar = {},
        // The map runs under the navigation bar now; keep Mapbox's logo and (i) button above it.
        logo = { Logo(contentPadding = PaddingValues(start = 4.dp, bottom = navBar + 4.dp)) },
        attribution = { Attribution(contentPadding = PaddingValues(start = 92.dp, bottom = navBar + 4.dp)) },
        mapViewportState = viewport,
        style = {
            MapboxStandardStyle(
                // Standard's middle slot: above roads, below labels, 3D buildings and the location
                // puck, so the route line never covers your position.
                middleSlot = {
                    RobotaxiLayers(robotaxiAreas)
                    drive?.route?.let { RouteLayers(it) }
                    if (starbase.show) StarbaseLayers(starbase.launch, darkMap = style.light == LightPreset.NIGHT || style.light == LightPreset.DUSK)
                },
                standardStyleState = standardStyle,
            )
        },
    ) {
        MapEffect(Unit) { mapView ->
            val map = mapView.mapboxMap
            map.getStyle { defaultColors = readDefaultColors(map) }
            trimmer.map = map
            mapView.location.addOnIndicatorPositionChangedListener {
                live.point = it
                trimmer.onPuckMoved(it)
            }
            mapView.location.addOnIndicatorBearingChangedListener { live.bearing = it }
        }
        // Past about zoom 17.5, or tilted further than 60°, a hand-moved camera can end up inside tall
        // 3D buildings (Mapbox doesn't stop that). The close-up drive view needs to go lower, but it
        // rides the road centre behind the puck, so the limits loosen only while it's on.
        MapEffect(closeUp) { mapView ->
            mapView.mapboxMap.setBounds(
                CameraBoundsOptions.Builder()
                    .maxZoom(if (closeUp) CLOSE_UP_MAX_ZOOM else MAX_ZOOM)
                    .maxPitch(if (closeUp) CLOSE_UP_MAX_PITCH else MAX_PITCH)
                    .build(),
            )
        }
        // Your position shows whenever location is allowed; the heading arrow only while driving.
        val driving = drive != null
        MapEffect(driving, hasLocation) { mapView ->
            val location = mapView.location
            // While driving, positions go through RouteSnappingProvider so the puck sits on the
            // route line instead of a few GPS metres beside it; plain GPS the rest of the time.
            val gps = gpsProvider ?: location.getLocationProvider()?.also { gpsProvider = it }
            if (gps != null) {
                location.setLocationProvider(if (driving) RouteSnappingProvider(gps) { trimmer.route } else gps)
            }
            location.updateSettings {
                enabled = hasLocation || driving
                locationPuck = createDefault2DPuck(withBearing = driving)
                puckBearingEnabled = driving
                puckBearing = PuckBearing.COURSE
            }
        }
    }
}

/** Looking at [pad] side-on, from where the stacking and the climb both show (see Pad.viewBearing). */
private fun starbaseCamera(pad: Pad) = cameraOptions {
    center(pad.tower)
    zoom(16.3)
    pitch(70.0)
    bearing(pad.viewBearing)
}

/** What StyledMap draws of Starbase: whether at all, whether to hide Standard's buildings, and which launch. */
private class StarbaseView(val show: Boolean, val hideBuildings: Boolean, val launch: StarshipLaunch?)

private const val MAX_ZOOM = 17.5
private const val MAX_PITCH = 60.0
private const val CLOSE_UP_MAX_ZOOM = 20.0
private const val CLOSE_UP_MAX_PITCH = 80.0

/** Each operator's service areas as a light tint with a solid outline in its colour. */
@Composable
private fun RobotaxiLayers(areas: Map<RobotaxiProvider, List<Feature>>) {
    for (provider in RobotaxiProvider.entries) {
        key(provider) {
            val source = rememberGeoJsonSourceState(key = provider.key)
            val features = areas[provider].orEmpty()
            LaunchedEffect(features) { source.data = GeoJSONData(features) }
            FillLayer(
                sourceState = source,
                fillLayerState = remember {
                    FillLayerState().apply {
                        fillColor = ColorValue(provider.color)
                        fillOpacity = DoubleValue(0.12)
                        // Same strength in every light preset, so the areas don't vanish at night.
                        fillEmissiveStrength = DoubleValue(1.0)
                    }
                },
            )
            LineLayer(
                sourceState = source,
                lineLayerState = remember {
                    LineLayerState().apply {
                        lineColor = ColorValue(provider.color)
                        lineWidth = DoubleValue(2.0)
                        lineOpacity = DoubleValue(0.85)
                        lineEmissiveStrength = DoubleValue(1.0)
                    }
                },
            )
        }
    }
}

/**
 * The drive route: a white outline under a blue line. The source keeps line metrics so the layers
 * can be trimmed by distance along the line, which is how RouteTrimmer erases the driven part.
 */
@Composable
private fun RouteLayers(route: Route) {
    val source = rememberGeoJsonSourceState(key = "minmap-route") { lineMetrics = BooleanValue(true) }
    LaunchedEffect(route) { source.data = GeoJSONData(Feature.fromGeometry(LineString.fromLngLats(route.points))) }
    LineLayer(
        sourceState = source,
        layerId = ROUTE_LAYER_IDS[0],
        lineLayerState = remember { routeLineState(Color.White, width = 13.0) },
    )
    LineLayer(
        sourceState = source,
        layerId = ROUTE_LAYER_IDS[1],
        lineLayerState = remember { routeLineState(Color(0xFF2F7BFF), width = 9.0) },
    )
}

private fun routeLineState(color: Color, width: Double) = LineLayerState().apply {
    lineColor = ColorValue(color)
    lineWidth = DoubleValue(width)
    lineCap = LineCapValue.ROUND
    lineJoin = LineJoinValue.ROUND
    // Stays bright under the Dusk and Night light presets instead of being shaded with the map.
    lineEmissiveStrength = DoubleValue(1.0)
}

/** The "default" of every colour option in the basemap's config schema, keyed by option name. */
private fun readDefaultColors(map: com.mapbox.maps.MapboxMap): Map<String, Value> {
    @Suppress("UNCHECKED_CAST")
    val schema = map.getStyleImportSchema(BASEMAP).value?.contents as? Map<String, Value> ?: return emptyMap()
    val keys = ColorTarget.entries.map { it.configKey }.toSet()
    return schema.filterKeys { it in keys }.mapNotNull { (key, option) ->
        @Suppress("UNCHECKED_CAST")
        val default = (option.contents as? Map<String, Value>)?.get("default") ?: return@mapNotNull null
        key to default
    }.toMap()
}

private const val BASEMAP = "basemap"

/**
 * Writes every setting the editor manages, every time, so switching presets and resetting undo
 * earlier changes. A colour the style doesn't override is set back to Standard's default from
 * [defaultColors]; until those are known (first moments after launch) it's left untouched.
 */
private fun StandardStyleConfigurationState.applyStyle(
    style: MapStyle,
    defaultColors: Map<String, Value>,
    hideBuildings: Boolean = false,
) {
    fun color(target: ColorTarget, set: (ColorValue) -> Unit) {
        val value = style.colors[target]?.let { ColorValue(Color(it)) }
            ?: defaultColors[target.configKey]?.let(::ColorValue)
            ?: return
        set(value)
    }
    fun shown(target: VisibilityTarget) = BooleanValue(target !in style.hidden)

    color(ColorTarget.LAND) { colorLand = it }
    color(ColorTarget.WATER) { colorWater = it }
    color(ColorTarget.GREENSPACE) { colorGreenspace = it }
    color(ColorTarget.BUILDINGS) { colorBuildings = it }
    color(ColorTarget.ROADS) { colorRoads = it }
    color(ColorTarget.TRUNKS) { colorTrunks = it }
    color(ColorTarget.MOTORWAYS) { colorMotorways = it }
    color(ColorTarget.COMMERCIAL) { colorCommercial = it }
    color(ColorTarget.EDUCATION) { colorEducation = it }
    color(ColorTarget.MEDICAL) { colorMedical = it }
    color(ColorTarget.INDUSTRIAL) { colorIndustrial = it }
    color(ColorTarget.BORDERS) { colorAdminBoundaries = it }
    color(ColorTarget.PLACE_LABELS) { colorPlaceLabels = it }
    color(ColorTarget.ROAD_LABELS) { colorRoadLabels = it }
    color(ColorTarget.POI_LABELS) { colorPointOfInterestLabels = it }
    // POI labels only take a custom colour in single-colour mode.
    colorModePointOfInterestLabels = if (ColorTarget.POI_LABELS in style.colors) {
        ColorModePointOfInterestLabelsValue.SINGLE
    } else {
        ColorModePointOfInterestLabelsValue.DEFAULT
    }

    showPlaceLabels = shown(VisibilityTarget.PLACE_LABELS)
    showRoadLabels = shown(VisibilityTarget.ROAD_LABELS)
    showPointOfInterestLabels = shown(VisibilityTarget.POINTS_OF_INTEREST)
    showTransitLabels = shown(VisibilityTarget.TRANSIT)
    showPedestrianRoads = shown(VisibilityTarget.PATHS)
    showAdminBoundaries = shown(VisibilityTarget.BORDERS)
    show3dBuildings = if (hideBuildings) BooleanValue(false) else shown(VisibilityTarget.BUILDINGS_3D)
    show3dLandmarks = shown(VisibilityTarget.LANDMARKS_3D)
    show3dTrees = shown(VisibilityTarget.TREES_3D)
    showLandmarkIcons = shown(VisibilityTarget.LANDMARK_ICONS)

    lightPreset = LightPresetValue(style.light.value)
    theme = ThemeValue(style.theme.value)
    densityPointOfInterestLabels = DoubleValue(style.poiDensity.toDouble())
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
