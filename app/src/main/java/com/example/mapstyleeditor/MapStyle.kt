package com.example.mapstyleeditor

import org.json.JSONObject

/**
 * A map feature whose colour can be changed. Each maps to one Mapbox Standard config property
 * (https://docs.mapbox.com/map-styles/standard/api/), so the exported JSON works on any Standard map.
 */
enum class ColorTarget(val label: String, val configKey: String) {
    LAND("Land", "colorLand"),
    WATER("Water", "colorWater"),
    GREENSPACE("Parks & woods", "colorGreenspace"),
    BUILDINGS("Buildings", "colorBuildings"),
    ROADS("Roads", "colorRoads"),
    TRUNKS("Main roads", "colorTrunks"),
    MOTORWAYS("Highways", "colorMotorways"),
    COMMERCIAL("Commercial areas", "colorCommercial"),
    EDUCATION("Schools", "colorEducation"),
    MEDICAL("Hospitals", "colorMedical"),
    INDUSTRIAL("Industry & airports", "colorIndustrial"),
    BORDERS("Borders", "colorAdminBoundaries"),
    PLACE_LABELS("Place names", "colorPlaceLabels"),
    ROAD_LABELS("Road names", "colorRoadLabels"),
    POI_LABELS("Points of interest", "colorPointOfInterestLabels"),
}

/** A map feature that can be shown or hidden. All of these default to shown. */
enum class VisibilityTarget(val label: String, val configKey: String) {
    PLACE_LABELS("Place names", "showPlaceLabels"),
    ROAD_LABELS("Road names & shields", "showRoadLabels"),
    POINTS_OF_INTEREST("Points of interest", "showPointOfInterestLabels"),
    TRANSIT("Transit", "showTransitLabels"),
    PATHS("Paths & trails", "showPedestrianRoads"),
    BORDERS("Borders", "showAdminBoundaries"),
    BUILDINGS_3D("3D buildings", "show3dBuildings"),
    LANDMARKS_3D("3D landmarks", "show3dLandmarks"),
    TREES_3D("3D trees", "show3dTrees"),
    LANDMARK_ICONS("Landmark icons", "showLandmarkIcons"),
}

enum class LightPreset(val label: String, val value: String) {
    DAWN("Dawn", "dawn"), DAY("Day", "day"), DUSK("Dusk", "dusk"), NIGHT("Night", "night"),
}

enum class Theme(val label: String, val value: String) {
    DEFAULT("Default", "default"), FADED("Faded", "faded"), MONOCHROME("Monochrome", "monochrome"),
}

const val DEFAULT_POI_DENSITY = 3

/** Everything the editor controls. Colours are ARGB ints; missing entries keep Mapbox's default. */
data class MapStyle(
    val colors: Map<ColorTarget, Int> = emptyMap(),
    val hidden: Set<VisibilityTarget> = emptySet(),
    val light: LightPreset = LightPreset.DAY,
    val theme: Theme = Theme.DEFAULT,
    val poiDensity: Int = DEFAULT_POI_DENSITY,
) {
    /**
     * Mapbox Standard config for this style, with only the settings that differ from the default.
     * This is both what the app saves and what the JSON tab exports.
     */
    fun toConfigJson(): String {
        val obj = JSONObject()
        if (light != LightPreset.DAY) obj.put("lightPreset", light.value)
        if (theme != Theme.DEFAULT) obj.put("theme", theme.value)
        if (poiDensity != DEFAULT_POI_DENSITY) obj.put("densityPointOfInterestLabels", poiDensity)
        for (target in ColorTarget.entries) {
            val color = colors[target] ?: continue
            obj.put(target.configKey, color.toHex())
        }
        // POI labels only take a custom colour in single-colour mode.
        if (ColorTarget.POI_LABELS in colors) obj.put("colorModePointOfInterestLabels", "single")
        for (target in VisibilityTarget.entries) {
            if (target in hidden) obj.put(target.configKey, false)
        }
        return obj.toString(2)
    }

    companion object {
        /** Parses config JSON written by [toConfigJson] (or by hand). Unknown keys are ignored. */
        fun fromConfigJson(json: String): MapStyle {
            val obj = JSONObject(json.trim())
            val colors = ColorTarget.entries
                .filter { obj.has(it.configKey) }
                .associateWith { parseHexOrNull(obj.getString(it.configKey)) ?: error("${it.configKey} is not a #RRGGBB colour") }
            return MapStyle(
                colors = colors,
                hidden = VisibilityTarget.entries.filter { obj.has(it.configKey) && !obj.getBoolean(it.configKey) }.toSet(),
                light = obj.optString("lightPreset").let { v -> LightPreset.entries.find { it.value == v } } ?: LightPreset.DAY,
                theme = obj.optString("theme").let { v -> Theme.entries.find { it.value == v } } ?: Theme.DEFAULT,
                poiDensity = obj.optInt("densityPointOfInterestLabels", DEFAULT_POI_DENSITY).coerceIn(1, 5),
            )
        }

        fun fromConfigJsonOrDefault(json: String?): MapStyle =
            json?.let { runCatching { fromConfigJson(it) }.getOrNull() } ?: MapStyle()
    }
}

fun Int.toHex(): String = String.format("#%06X", this and 0xFFFFFF)

fun parseHex(hex: String): Int = (0xFF000000 or hex.removePrefix("#").toLong(16)).toInt()

fun parseHexOrNull(hex: String): Int? {
    val clean = hex.trim().removePrefix("#")
    if (clean.length != 6 || clean.any { it !in "0123456789abcdefABCDEF" }) return null
    return parseHex(clean)
}

/** Returns an error message if [json] isn't usable style config, or null if it is. */
fun validateConfigJson(json: String): String? = try {
    MapStyle.fromConfigJson(json)
    null
} catch (e: Exception) {
    e.message ?: "Not valid JSON"
}

data class Preset(val name: String, val style: MapStyle, val swatch: List<String>? = null)

private fun colors(vararg pairs: Pair<ColorTarget, String>) = pairs.associate { (k, v) -> k to parseHex(v) }

val PRESETS = listOf(
    Preset("Standard", MapStyle()),
    Preset(
        "Night",
        MapStyle(light = LightPreset.NIGHT),
        swatch = listOf("#1B2233", "#0E1A2E", "#1E2B2A", "#3A4356", "#6B5B3E"),
    ),
    Preset(
        "Dusk",
        MapStyle(light = LightPreset.DUSK),
        swatch = listOf("#5B4A6B", "#3C4F78", "#4F5A55", "#8C7A8F", "#C98E5A"),
    ),
    Preset(
        "Monochrome",
        MapStyle(theme = Theme.MONOCHROME),
        swatch = listOf("#EDEDED", "#C4C4C4", "#DADADA", "#FFFFFF", "#BDBDBD"),
    ),
    Preset(
        "Silver",
        MapStyle(
            colors = colors(
                ColorTarget.LAND to "#F5F5F5", ColorTarget.BUILDINGS to "#EEEEEE",
                ColorTarget.WATER to "#C9C9C9", ColorTarget.GREENSPACE to "#E5E5E5",
                ColorTarget.ROADS to "#FFFFFF", ColorTarget.TRUNKS to "#FFFFFF",
                ColorTarget.MOTORWAYS to "#DADADA",
                ColorTarget.PLACE_LABELS to "#616161", ColorTarget.ROAD_LABELS to "#616161",
            ),
            hidden = setOf(VisibilityTarget.LANDMARK_ICONS),
        ),
    ),
    Preset(
        "Retro",
        MapStyle(
            colors = colors(
                ColorTarget.LAND to "#DFD2AE", ColorTarget.BUILDINGS to "#E8DCC0",
                ColorTarget.WATER to "#B9D3C2", ColorTarget.GREENSPACE to "#A5B076",
                ColorTarget.ROADS to "#F5F1E6", ColorTarget.TRUNKS to "#FDFCF8",
                ColorTarget.MOTORWAYS to "#F8C967", ColorTarget.BORDERS to "#C9B2A6",
                ColorTarget.PLACE_LABELS to "#523735", ColorTarget.ROAD_LABELS to "#523735",
            ),
        ),
    ),
    Preset(
        "Aubergine",
        MapStyle(
            colors = colors(
                ColorTarget.LAND to "#1D2C4D", ColorTarget.BUILDINGS to "#283D6A",
                ColorTarget.WATER to "#0E1626", ColorTarget.GREENSPACE to "#023E58",
                ColorTarget.ROADS to "#304A7D", ColorTarget.TRUNKS to "#3A5A94",
                ColorTarget.MOTORWAYS to "#2C6675", ColorTarget.BORDERS to "#4B6878",
                ColorTarget.PLACE_LABELS to "#8EC3B9", ColorTarget.ROAD_LABELS to "#8EC3B9",
            ),
        ),
    ),
    Preset(
        "Minimal",
        MapStyle(
            colors = colors(
                ColorTarget.LAND to "#FFFFFF", ColorTarget.BUILDINGS to "#F2F2F2",
                ColorTarget.WATER to "#DCE6EE", ColorTarget.GREENSPACE to "#EEF3EA",
                ColorTarget.ROADS to "#EBEBEB", ColorTarget.MOTORWAYS to "#D6D6D6",
                ColorTarget.PLACE_LABELS to "#8A8A8A", ColorTarget.ROAD_LABELS to "#8A8A8A",
            ),
            hidden = setOf(
                VisibilityTarget.POINTS_OF_INTEREST, VisibilityTarget.TRANSIT,
                VisibilityTarget.LANDMARK_ICONS, VisibilityTarget.TREES_3D,
            ),
            theme = Theme.FADED,
        ),
    ),
)
