package com.example.mapstyleeditor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.mapstyleeditor.account.MapboxAccount
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mapstyleeditor.ColorTarget
import com.example.mapstyleeditor.EditorState
import com.example.mapstyleeditor.MapStyle
import com.example.mapstyleeditor.PRESETS
import com.example.mapstyleeditor.VisibilityTarget
import com.example.mapstyleeditor.parseHex
import com.example.mapstyleeditor.parseHexOrNull
import com.example.mapstyleeditor.toHex
import com.example.mapstyleeditor.LightPreset
import com.example.mapstyleeditor.Theme
import com.example.mapstyleeditor.validateConfigJson
import kotlin.math.roundToInt

private enum class EditorTab(val title: String) {
    PRESETS("Presets"), COLORS("Colors"), SHOW_HIDE("Show / Hide"), LIGHT("Light"), JSON("JSON"),
}

/** Approximate Mapbox Standard daytime colours, used to preview features the user hasn't recoloured yet. */
private val DEFAULT_COLORS = mapOf(
    ColorTarget.LAND to "#F2EFE9", ColorTarget.WATER to "#8DD0EE", ColorTarget.GREENSPACE to "#C2E3A9",
    ColorTarget.BUILDINGS to "#E4E0DA", ColorTarget.ROADS to "#FFFFFF", ColorTarget.TRUNKS to "#F6DDA5",
    ColorTarget.MOTORWAYS to "#F5C46E", ColorTarget.COMMERCIAL to "#F3E3E3", ColorTarget.EDUCATION to "#EAE2F0",
    ColorTarget.MEDICAL to "#F6DEDE", ColorTarget.INDUSTRIAL to "#E4E2E8", ColorTarget.BORDERS to "#A99CB5",
    ColorTarget.PLACE_LABELS to "#3D3D3D", ColorTarget.ROAD_LABELS to "#575757", ColorTarget.POI_LABELS to "#5E6A7A",
).mapValues { parseHex(it.value) }

private val QUICK_COLORS = listOf(
    "#FFFFFF", "#E0E0E0", "#9E9E9E", "#424242", "#000000", "#1A237E", "#1E88E5", "#81D4FA",
    "#00897B", "#43A047", "#9E9D24", "#E6D5A8", "#FB8C00", "#E53935", "#F48FB1", "#8E24AA",
).map(::parseHex)

private fun MapStyle.colorOf(target: ColorTarget): Int = colors[target] ?: DEFAULT_COLORS.getValue(target)

private val SWATCH_TARGETS =
    listOf(ColorTarget.LAND, ColorTarget.WATER, ColorTarget.GREENSPACE, ColorTarget.ROADS, ColorTarget.MOTORWAYS)

/**
 * The editor's tabs and their contents, with no background of its own: it's drawn on the frosted
 * style menu (see StyleMenu). Fills the height it's given.
 */
@Composable
fun EditorContent(editor: EditorState, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableStateOf(EditorTab.PRESETS) }

    Column(modifier) {
        Text(
            "Map style",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp),
        )
        PrimaryScrollableTabRow(
            selectedTabIndex = tab.ordinal,
            edgePadding = 8.dp,
            containerColor = Color.Transparent,
            divider = {},
        ) {
            EditorTab.entries.forEach { t ->
                Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.title) })
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                EditorTab.PRESETS -> PresetsTab(editor)
                EditorTab.COLORS -> ColorsTab(editor)
                EditorTab.SHOW_HIDE -> ShowHideTab(editor)
                EditorTab.LIGHT -> LightTab(editor)
                EditorTab.JSON -> JsonTab(editor)
            }
        }
        MapboxAccountRow()
    }
}

/** Which Mapbox account the map runs on, and a way to switch (back to the sign-in screen). */
@Composable
private fun MapboxAccountRow() {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (MapboxAccount.user.isNotEmpty()) "Mapbox: ${MapboxAccount.user}" else "Mapbox account connected",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { MapboxAccount.signOut(context) }) { Text("Sign out") }
    }
}

// ---------------------------------------------------------------- Presets

@Composable
private fun PresetsTab(editor: EditorState) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(PRESETS) { preset ->
            val selected = editor.style == preset.style
            OutlinedCard(
                onClick = { editor.applyPreset(preset) },
                border = BorderStroke(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(6.dp))) {
                        // Light-preset and theme presets don't set colours, so they carry their own swatch.
                        val swatch = preset.swatch?.map(::parseHex) ?: SWATCH_TARGETS.map(preset.style::colorOf)
                        swatch.forEach { c -> Box(Modifier.weight(1f).fillMaxSize().background(Color(c))) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(preset.name, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Colors

@Composable
private fun ColorsTab(editor: EditorState) {
    var expanded by rememberSaveable { mutableStateOf<ColorTarget?>(null) }
    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
        items(ColorTarget.entries) { target ->
            val custom = editor.style.colors[target]
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = if (expanded == target) null else target }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColorDot(editor.style.colorOf(target), size = 28)
                Spacer(Modifier.width(12.dp))
                Text(target.label, modifier = Modifier.weight(1f))
                Text(
                    custom?.toHex() ?: "Default",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(if (expanded == target) " ▴" else " ▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded == target) {
                ColorEditor(
                    target = target,
                    start = editor.style.colorOf(target),
                    isCustom = custom != null,
                    onChange = { c -> editor.update { it.copy(colors = it.colors + (target to c)) } },
                    onReset = { editor.update { it.copy(colors = it.colors - target) } },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

private data class Hsv(val h: Float, val s: Float, val v: Float) {
    fun toColor(): Int = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))

    companion object {
        fun of(color: Int): Hsv {
            val out = FloatArray(3)
            android.graphics.Color.colorToHSV(color, out)
            return Hsv(out[0], out[1], out[2])
        }
    }
}

@Composable
private fun ColorEditor(
    target: ColorTarget,
    start: Int,
    isCustom: Boolean,
    onChange: (Int) -> Unit,
    onReset: () -> Unit,
) {
    // Kept as HSV locally so hue isn't lost while the colour passes through grey.
    var hsv by remember(target) { mutableStateOf(Hsv.of(start)) }
    var hexText by remember(target) { mutableStateOf(start.toHex()) }

    fun push(newHsv: Hsv) {
        hsv = newHsv
        val color = newHsv.toColor()
        hexText = color.toHex()
        onChange(color)
    }

    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QUICK_COLORS.forEach { c ->
                Box(Modifier.clip(CircleShape).clickable { push(Hsv.of(c)) }) { ColorDot(c, size = 32) }
            }
        }
        Spacer(Modifier.height(8.dp))
        GradientSlider(
            label = "Hue",
            value = hsv.h,
            range = 0f..360f,
            brush = Brush.horizontalGradient(
                (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) },
            ),
            onChange = { push(hsv.copy(h = it)) },
        )
        GradientSlider(
            label = "Saturation",
            value = hsv.s,
            range = 0f..1f,
            brush = Brush.horizontalGradient(listOf(Color(hsv.copy(s = 0f).toColor()), Color(hsv.copy(s = 1f).toColor()))),
            onChange = { push(hsv.copy(s = it)) },
        )
        GradientSlider(
            label = "Brightness",
            value = hsv.v,
            range = 0f..1f,
            brush = Brush.horizontalGradient(listOf(Color.Black, Color(hsv.copy(v = 1f).toColor()))),
            onChange = { push(hsv.copy(v = it)) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = hexText,
                onValueChange = { text ->
                    hexText = text
                    parseHexOrNull(text)?.let { c ->
                        hsv = Hsv.of(c)
                        onChange(c)
                    }
                },
                label = { Text("Hex") },
                singleLine = true,
                isError = parseHexOrNull(hexText) == null,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                enabled = isCustom,
                onClick = {
                    onReset()
                    val default = DEFAULT_COLORS.getValue(target)
                    hsv = Hsv.of(default)
                    hexText = default.toHex()
                },
            ) { Text("Reset") }
        }
    }
}

@Composable
private fun GradientSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    brush: Brush,
    onChange: (Float) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(brush),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onSurface,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun ColorDot(color: Int, size: Int) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}

// ---------------------------------------------------------------- Show / Hide

@Composable
private fun ShowHideTab(editor: EditorState) {
    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Robotaxi areas")
                    Text(
                        "Waymo, Tesla and Zoox service areas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = editor.showRobotaxi, onCheckedChange = editor::toggleRobotaxi)
            }
        }
        items(VisibilityTarget.entries) { target ->
            val visible = target !in editor.style.hidden
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(target.label, modifier = Modifier.weight(1f))
                Switch(
                    checked = visible,
                    onCheckedChange = { show ->
                        editor.update { it.copy(hidden = if (show) it.hidden - target else it.hidden + target) }
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Light

@Composable
private fun LightTab(editor: EditorState) {
    val style = editor.style
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Time of day", style = MaterialTheme.typography.labelLarge)
        Text(
            "Changes the lighting and shadows across the whole map.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChipRow(LightPreset.entries, style.light, LightPreset::label) { v -> editor.update { it.copy(light = v) } }

        Spacer(Modifier.height(12.dp))
        Text("Theme", style = MaterialTheme.typography.labelLarge)
        Text(
            "A colour filter over everything. Colours you set yourself are filtered too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChipRow(Theme.entries, style.theme, Theme::label) { v -> editor.update { it.copy(theme = v) } }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Points of interest", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                when (style.poiDensity) { 1 -> "Fewest"; 5 -> "Most"; else -> "${style.poiDensity} of 5" },
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Slider(
            value = style.poiDensity.toFloat(),
            onValueChange = { v -> editor.update { it.copy(poiDensity = v.roundToInt()) } },
            valueRange = 1f..5f,
            steps = 3,
        )

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { editor.applyPreset(PRESETS.first()) }) { Text("Reset everything to standard") }
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

// ---------------------------------------------------------------- JSON

@Composable
private fun JsonTab(editor: EditorState) {
    val context = LocalContext.current
    var showImport by remember { mutableStateOf(false) }
    val json = editor.style.toConfigJson()
    val isStandard = json.trim() == "{}"

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "Mapbox Standard config. Paste it as the basemap import's \"config\" in any Mapbox map.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Map style JSON", json))
                // Android 13+ shows its own clipboard confirmation.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Copy") }
            FilledTonalButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, json)
                context.startActivity(Intent.createChooser(send, "Share map style"))
            }) { Text("Share") }
            OutlinedButton(onClick = { showImport = true }) { Text("Import") }
        }
        Spacer(Modifier.height(8.dp))
        SelectionContainer(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            Text(
                if (isStandard) "{}\n\nStandard Mapbox style: nothing has been changed yet." else json,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            onImport = {
                editor.import(it)
                showImport = false
            },
        )
    }
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import style JSON") },
        text = {
            Column {
                Text(
                    "Paste Mapbox Standard config JSON, for example one exported from this app. " +
                        "Settings this app doesn't edit are ignored.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    minLines = 6,
                    maxLines = 12,
                    isError = error != null,
                    supportingText = error?.let { msg -> { Text(msg) } },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    error = validateConfigJson(text)
                    if (error == null) onImport(text)
                },
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
