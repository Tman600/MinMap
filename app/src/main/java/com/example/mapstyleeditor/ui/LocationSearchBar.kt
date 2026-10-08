package com.example.mapstyleeditor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mapstyleeditor.robotaxi.RobotaxiProvider
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.mapstyleeditor.places.PlaceSearch
import com.example.mapstyleeditor.places.SearchHit
import com.example.mapstyleeditor.places.formatDistance
import com.mapbox.geojson.Point

/** A search result: what to show in the list and where to move the camera. */
data class Place(val name: String, val detail: String, val longitude: Double, val latitude: Double)

/** A learned destination offered above the search bar, with the current drive time if known. */
data class SuggestedDrive(val place: Place, val minutes: Int?)

/**
 * Frosted-glass pill for finding a place. Suggestions appear above it while typing; picking one
 * (or pressing search on the keyboard) calls [onPlace]. [hazeState] must be the same state the map
 * is registered with via `hazeSource`, so the glass blurs the map behind it.
 */
@Composable
fun LocationSearchBar(
    hazeState: HazeState,
    accessToken: String,
    darkMap: Boolean,
    onPlace: (Place) -> Unit,
    onDrive: (Place) -> Unit,
    /** Robotaxi operators serving a place, for the buttons next to Drive. */
    robotaxiAt: (Place) -> List<RobotaxiProvider>,
    /** Learned "you usually go here around now" places, shown while the bar is empty. */
    suggestions: List<SuggestedDrive>,
    onForgetSuggestion: (Place) -> Unit,
    /** Where the map is looking, so results near it come first. */
    near: () -> Point?,
    /** Where you are, for the distances shown. */
    here: () -> Point?,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var showResults by remember { mutableStateOf(false) }
    /** The category being listed ("Coffee Shop"), while the list shows its nearby places. */
    var browsing by remember { mutableStateOf<String?>(null) }
    /** The place the map last flew to; offers to drive there until the search changes. */
    var picked by remember { mutableStateOf<Place?>(null) }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val search = remember(accessToken) { PlaceSearch(accessToken) }

    // Search as the user types, but only once they pause, so we don't fire a request per keystroke.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        if (query == browsing) return@LaunchedEffect
        browsing = null
        delay(350)
        results = search.suggest(query, near(), here())
    }

    fun show(place: Place) {
        query = place.name
        picked = place
        showResults = false
        browsing = null
        focus.clearFocus()
        onPlace(place)
    }

    // A category opens into its nearest places; anything else is looked up and flown to.
    fun pick(hit: SearchHit) {
        scope.launch {
            val category = hit.category
            if (category != null) {
                browsing = hit.name
                query = hit.name
                results = search.category(category, near(), here())
                showResults = true
            } else {
                search.open(hit)?.let(::show)
            }
        }
    }

    // The map underneath decides whether light or dark glass reads better.
    val ink = if (darkMap) Color.White else Color(0xFF1C1C1E)
    val glass = remember(darkMap) { glassStyle(darkMap) }
    val glassBorder = if (darkMap) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.6f)

    Column(
        modifier.fillMaxWidth(0.82f).widthIn(max = 460.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(visible = showResults && results.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            val cardShape = RoundedCornerShape(22.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .hazeBlur(HazeInput.Sources(hazeState), style = glass)
                    .border(1.dp, glassBorder, cardShape)
                    // With the keyboard up there's little room above the bar; scroll rather than
                    // push the bar itself off screen.
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
            ) {
                results.forEach { hit ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { pick(hit) }
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(hit.name, color = ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val detail = listOfNotNull(hit.distance?.let(::formatDistance), hit.detail.ifBlank { null })
                            if (detail.isNotEmpty()) {
                                Text(
                                    detail.joinToString(" · "),
                                    color = ink.copy(alpha = 0.65f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        // A category opens into a list rather than going straight to the map.
                        if (hit.category != null) Text("›", color = ink.copy(alpha = 0.5f), fontSize = 18.sp)
                    }
                }
            }
        }

        val pill = RoundedCornerShape(50)
        val destination = picked
        AnimatedVisibility(visible = destination != null && !showResults, enter = fadeIn(), exit = fadeOut()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .clip(pill)
                        .hazeBlur(HazeInput.Sources(hazeState), style = glass)
                        .border(1.dp, glassBorder, pill)
                        .clickable { destination?.let(onDrive) }
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Drive", color = ink, fontSize = 15.sp)
                    Text("  ›", color = ink.copy(alpha = 0.6f), fontSize = 16.sp)
                }
                // Robotaxi operators that serve the picked place, as an alternative to driving.
                destination?.let(robotaxiAt).orEmpty().forEach { provider ->
                    RobotaxiPill(
                        provider, ink, glassBorder,
                        Modifier.clip(pill).hazeBlur(HazeInput.Sources(hazeState), style = glass),
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(pill)
                .hazeBlur(HazeInput.Sources(hazeState), style = glass)
                .border(1.dp, glassBorder, pill)
                .padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchGlyph(ink.copy(alpha = 0.7f))
            Spacer(Modifier.size(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    // Shorter when a suggestion shares the bar.
                    Text(
                        if (suggestions.isEmpty()) "Search for a place" else "Search",
                        color = ink.copy(alpha = 0.55f),
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        picked = null
                        showResults = true
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = ink, fontSize = 16.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        scope.launch {
                            // Use the latest suggestions, or search right away if the pause hasn't passed yet.
                            val list = results.ifEmpty { search.suggest(query, near(), here()) }
                            list.firstOrNull()?.let(::pick)
                        }
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // The learned destination for right now, sitting at the right of the bar: tap to drive
            // there, long-press to stop suggesting it. It never starts anything on its own.
            val suggestion = suggestions.firstOrNull()
            if (query.isEmpty() && suggestion != null) {
                Row(
                    Modifier
                        .widthIn(max = 200.dp)
                        .clip(pill)
                        .combinedClickable(
                            onClick = { onDrive(suggestion.place) },
                            onLongClick = { onForgetSuggestion(suggestion.place) },
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("→ ", color = ink.copy(alpha = 0.55f), fontSize = 15.sp)
                    Text(
                        suggestion.place.name,
                        color = ink,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    suggestion.minutes?.let {
                        Text(" · $it min", color = ink.copy(alpha = 0.55f), fontSize = 14.sp, maxLines = 1)
                    }
                }
            }
            if (query.isNotEmpty()) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            query = ""
                            picked = null
                            browsing = null
                            showResults = false
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", color = ink.copy(alpha = 0.6f), fontSize = 14.sp)
                }
            }
        }
    }
}

internal fun glassStyle(dark: Boolean) = HazeBlurStyle {
    blurRadius(22.dp)
    noiseFactor(0.12f)
    colorEffects(
        listOf(HazeColorEffect.tint(if (dark) Color.Black.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.45f))),
    )
    // Phones without blur support (Android 11 and older) get a denser tint instead, so text stays readable.
    fallbackColorEffect(
        HazeColorEffect.tint(if (dark) Color(0xE61C1C1E) else Color(0xE6F7F7F7)),
    )
}

/** A magnifying glass, drawn so the app doesn't need an icon library for one glyph. */
@Composable
private fun SearchGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 2.dp.toPx()
        val radius = size.minDimension * 0.34f
        val center = Offset(radius + stroke / 2, radius + stroke / 2)
        drawCircle(color, radius, center, style = Stroke(stroke))
        val start = center + Offset(radius * 0.72f, radius * 0.72f)
        drawLine(color, start, Offset(size.width - stroke / 2, size.height - stroke / 2), stroke, StrokeCap.Round)
    }
}

