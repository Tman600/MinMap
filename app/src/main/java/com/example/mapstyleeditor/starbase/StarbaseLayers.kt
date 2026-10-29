package com.example.mapstyleeditor.starbase

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.mapbox.geojson.Feature
import com.mapbox.geojson.Point
import com.mapbox.maps.extension.compose.style.DoubleListValue
import com.mapbox.maps.extension.compose.style.DoubleValue
import com.mapbox.maps.extension.compose.style.LongValue
import com.mapbox.maps.extension.compose.style.layers.ModelIdValue
import com.mapbox.maps.extension.compose.style.layers.generated.ModelLayer
import com.mapbox.maps.extension.compose.style.layers.generated.ModelLayerState
import com.mapbox.maps.extension.compose.style.layers.generated.ModelTypeValue
import com.mapbox.maps.extension.compose.style.sources.GeoJSONData
import com.mapbox.maps.extension.compose.style.sources.generated.GeoJsonSourceState
import com.mapbox.maps.extension.compose.style.sources.generated.rememberGeoJsonSourceState

/** Middle of the launch site, for deciding when the map is looking at Starbase. */
val STARBASE: Point = Point.fromLngLat(-97.1564, 25.9966)

/**
 * Starbase's launch towers, chopsticks and mounts as 3D models, for Standard's middle slot, plus the
 * Starship standing in the build site's rocket garden. Each part is its own model layer anchored at
 * its pad's tower (or its own spot) and placed by Mapbox's model translation and rotation (see
 * StarbaseScene). The models ship with the app, so this costs no Mapbox requests.
 */
@Composable
fun StarbaseLayers(darkMap: Boolean, showBays: Boolean) {
    for (pad in Pad.entries) {
        key(pad) {
            val source = rememberGeoJsonSourceState(key = "minmap-starbase-${pad.name.lowercase()}")
            LaunchedEffect(Unit) { source.data = GeoJSONData(Feature.fromGeometry(pad.tower)) }
            for (placed in STARBASE_PARTS) {
                if (placed.pad != pad) continue
                key(placed.part) { ModelPart(source, placed.key, placed.part.asset, placed.pose, darkMap) }
            }
        }
    }
    for (vehicle in OnDisplay.entries) {
        key(vehicle) {
            val source = rememberGeoJsonSourceState(key = vehicle.key)
            LaunchedEffect(Unit) { source.data = GeoJSONData(Feature.fromGeometry(vehicle.at)) }
            ModelPart(source, vehicle.key, vehicle.asset, Pose(0.0, 0.0, 0.0, vehicle.heading), darkMap)
        }
    }
    if (showBays) {
        for (bay in MegaBay.entries) {
            key(bay) {
                val source = rememberGeoJsonSourceState(key = bay.key)
                LaunchedEffect(Unit) { source.data = GeoJSONData(Feature.fromGeometry(bay.at)) }
                ModelPart(source, bay.key, "mega-bay", Pose(0.0, 0.0, 0.0, bay.heading), darkMap)
                ModelPart(source, "${bay.key}-booster", "booster", Pose(0.0, 0.0, 0.0, bay.heading), darkMap)
            }
        }
    }
}

@Composable
private fun ModelPart(source: GeoJsonSourceState, layerId: String, asset: String, pose: Pose, darkMap: Boolean) {
    val state = remember {
        ModelLayerState().apply {
            modelId = ModelIdValue("minmap-starbase-$asset", "asset://starbase/$asset.glb")
            modelType = ModelTypeValue.COMMON_3D
            // Not drawn (or loaded into view) until the map is close enough to make them out.
            minZoom = LongValue(11)
            modelTranslation = DoubleListValue(pose.east, pose.south, pose.up)
            modelRotation = DoubleListValue(0.0, 0.0, pose.heading)
        }
    }
    // The pads are floodlit at night; unlit, the steel would all but vanish on a dark map.
    SideEffect { state.modelEmissiveStrength = DoubleValue(if (darkMap) NIGHT_GLOW else 0.0) }
    ModelLayer(sourceState = source, layerId = layerId, modelLayerState = state)
}

private const val NIGHT_GLOW = 0.55
