package com.example.mapstyleeditor.starbase

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mapbox.geojson.Feature
import com.mapbox.geojson.Point
import com.mapbox.maps.extension.compose.style.BooleanValue
import com.mapbox.maps.extension.compose.style.ColorValue
import com.mapbox.maps.extension.compose.style.DoubleListValue
import com.mapbox.maps.extension.compose.style.DoubleValue
import com.mapbox.maps.extension.compose.style.LongValue
import com.mapbox.maps.extension.compose.style.layers.ModelIdValue
import com.mapbox.maps.extension.compose.style.layers.generated.ModelLayer
import com.mapbox.maps.extension.compose.style.layers.generated.ModelLayerState
import com.mapbox.maps.extension.compose.style.layers.generated.ModelTypeValue
import com.mapbox.maps.extension.compose.style.layers.generated.VisibilityValue
import com.mapbox.maps.extension.compose.style.sources.GeoJSONData
import com.mapbox.maps.extension.compose.style.sources.generated.GeoJsonSourceState
import com.mapbox.maps.extension.compose.style.sources.generated.rememberGeoJsonSourceState
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Middle of the launch site, for deciding when the map is looking at Starbase. */
val STARBASE: Point = Point.fromLngLat(-97.1564, 25.9966)

/**
 * Starbase's towers, chopsticks, mounts and (around a launch) Super Heavy, Starship and their
 * flames, venting and deluge steam as 3D models, for Standard's middle slot. Each part is its own
 * model layer anchored at its pad's tower and moved by Mapbox's model translation, rotation and
 * scale (see StarbaseScene). Redrawn every frame only while something is moving; otherwise once a
 * second, enough to catch the next step starting.
 */
@Composable
fun StarbaseLayers(launch: StarshipLaunch?, darkMap: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(launch) {
        while (true) {
            if (starbaseMoving(launch, System.currentTimeMillis())) {
                withFrameMillis { now = System.currentTimeMillis() }
            } else {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    val scene = starbaseScene(launch, now)
    for (pad in Pad.entries) {
        key(pad) {
            val source = rememberGeoJsonSourceState(key = "minmap-starbase-${pad.name.lowercase()}")
            LaunchedEffect(Unit) { source.data = GeoJSONData(Feature.fromGeometry(pad.tower)) }
            for (placed in scene) {
                if (placed.pad != pad) continue
                key(placed.part) { PartLayer(source, placed, darkMap) }
            }
        }
    }
}

@Composable
private fun PartLayer(source: GeoJsonSourceState, placed: PlacedPart, darkMap: Boolean) {
    val effect = placed.part.asset in EFFECTS
    val state = remember {
        ModelLayerState().apply {
            val asset = placed.part.asset
            modelId = ModelIdValue("minmap-starbase-$asset", "asset://starbase/$asset.glb")
            modelType = ModelTypeValue.COMMON_3D
            // Not drawn (or loaded into view) until the map is close enough to make them out.
            minZoom = LongValue(11)
            // Frost is the steel's colour mixed towards white (see Pose.frost).
            modelColor = ColorValue(Color.White)
            if (effect) modelCastShadows = BooleanValue(false)
        }
    }
    val pose = placed.pose
    SideEffect {
        state.visibility = if (pose == null) VisibilityValue.NONE else VisibilityValue.VISIBLE
        // The pads are floodlit at night; unlit, the steel would all but vanish on a dark map. The
        // effects light themselves.
        if (!effect) state.modelEmissiveStrength = DoubleValue(if (darkMap) NIGHT_GLOW else 0.0)
        if (pose != null) {
            state.modelTranslation = DoubleListValue(pose.east, pose.south, pose.up)
            state.modelRotation = rotation(pose)
            state.modelScale = DoubleListValue(pose.scale, pose.scale, pose.scale)
            state.modelOpacity = DoubleValue(pose.opacity)
            state.modelColorMixIntensity = DoubleValue(pose.frost)
        }
    }
    ModelLayer(sourceState = source, layerId = placed.key, modelLayerState = state)
}

/**
 * Mapbox's model rotation, Euler degrees [x, y, z]: z turns the model clockwise (its heading), x
 * and y lean it; a lean towards a compass bearing splits between them by its north and east parts.
 */
private fun rotation(p: Pose): DoubleListValue {
    val b = Math.toRadians(p.tiltToward)
    return DoubleListValue(TILT_X * p.tilt * cos(b), TILT_Y * p.tilt * sin(b), p.heading)
}

/**
 * Which way each of Mapbox's lean axes tips the model. Positive y leans it east (checked from
 * above on the emulator); x is taken to lean it north the same way. Flights head almost due east,
 * so x only ever carries a few degrees.
 */
private const val TILT_X = 1.0
private const val TILT_Y = 1.0
private const val NIGHT_GLOW = 0.55
private val EFFECTS = setOf("flame", "vapor", "steam")

/**
 * The launch line at the top of the map while it's looking at Starbase: a live countdown around a
 * real flight, the mission clock and an End button during a replay, or the offer to replay the
 * last flight.
 */
@Composable
fun StarbasePill(
    hazeState: HazeState,
    glass: HazeBlurStyle,
    darkMap: Boolean,
    live: StarshipLaunch?,
    replay: StarshipLaunch?,
    lastFlight: StarshipLaunch?,
    onReplay: () -> Unit,
    onEndReplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val liveSoon = live?.takeIf { it.status != "TBD" && (now - it.net) in -2 * HOUR_MS..15 * MINUTE_MS }
    val text: String
    var tapToReplay = false
    when {
        replay != null -> text = "${replay.name} replay  ${clock(now - replay.net)}"
        liveSoon != null -> text = "${liveSoon.name}  ${clock(now - liveSoon.net)}"
        lastFlight != null -> {
            text = "Replay ${lastFlight.name}"
            tapToReplay = true
        }
        else -> return
    }
    val ink = if (darkMap) Color.White else Color(0xFF1C1C1E)
    val edge = if (darkMap) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.6f)
    val pill = RoundedCornerShape(50)
    Row(
        modifier
            .clip(pill)
            .hazeBlur(HazeInput.Sources(hazeState), style = glass)
            .border(1.dp, edge, pill)
            .then(if (tapToReplay) Modifier.clickable(onClick = onReplay) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Monospaced while a clock is running, so the digits don't jiggle.
        Text(text, color = ink, fontSize = 14.sp, fontFamily = if (tapToReplay) FontFamily.Default else FontFamily.Monospace)
        if (replay != null) {
            Text(
                "End",
                color = Color(0xFFE5484D),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 14.dp).clickable(onClick = onEndReplay),
            )
        }
    }
}

/** Mission time: "T-01:05:09" before liftoff, "T+06:57" after. */
private fun clock(sinceLiftoff: Long): String {
    val s = abs(sinceLiftoff) / 1000
    val sign = if (sinceLiftoff < 0) "T-" else "T+"
    return if (s >= 3600) "%s%02d:%02d:%02d".format(sign, s / 3600, s / 60 % 60, s % 60)
    else "%s%02d:%02d".format(sign, s / 60, s % 60)
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS

/** A replay of [flight] that starts [leadSeconds] before its liftoff, from now. */
fun replayOf(flight: StarshipLaunch, leadSeconds: Int = 75): StarshipLaunch =
    flight.copy(net = System.currentTimeMillis() + leadSeconds * 1000L, status = "Success")

/** When a replay is over: a minute after the booster is home (or splashed down) and the ship gone. */
fun replayDone(replay: StarshipLaunch, now: Long = System.currentTimeMillis()): Boolean {
    val end = maxOf(replay.timeline["Stage 1 Landing"] ?: 417.0, 600.0) + 60
    return now - replay.net > end * 1000
}

/**
 * A pretend launch for trying this out (debug builds only), started from adb:
 *
 *   adb shell am start -n app.minmap/com.example.mapstyleeditor.MainActivity \
 *     --es demo_starbase 20 --es demo_starbase_pad 2 --ez demo_starbase_catch true
 *
 * demo_starbase is the number of seconds until liftoff (negative: that long ago); 1e10 only
 * opens the map at Starbase, with the real launch data.
 */
fun demoStarbaseLaunch(intent: Intent?, appInfo: ApplicationInfo): StarshipLaunch? {
    if (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
    val seconds = intent?.getStringExtra("demo_starbase")?.toDoubleOrNull() ?: return null
    // A long way off: just look at the pad, with the real launch data (for trying the replay).
    if (seconds > 1e9) return StarshipLaunch("", Long.MAX_VALUE, Pad.PAD_2, "TBD", false)
    return StarshipLaunch(
        name = "Demo flight",
        net = System.currentTimeMillis() + (seconds * 1000).toLong(),
        pad = if (intent.getStringExtra("demo_starbase_pad") == "1") Pad.PAD_1 else Pad.PAD_2,
        status = "Go",
        boosterCatch = intent.getBooleanExtra("demo_starbase_catch", true),
    )
}
