package com.example.mapstyleeditor.ui

import com.example.mapstyleeditor.update.AppUpdates
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.example.mapstyleeditor.EditorState
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import kotlin.math.hypot

/**
 * The style editor as a corner menu: a small frosted button top-right that, when tapped, grows
 * the menu out of itself as an expanding circle of frosted glass, and shrinks back into it on
 * close (button, a tap on the map, or Back).
 */
@Composable
fun StyleMenu(
    editor: EditorState,
    hazeState: HazeState,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val glass = remember(dark) { glassStyle(dark) }
    val ink = if (dark) Color.White else Color(0xFF1C1C1E)
    val edge = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.6f)
    val reveal by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 320f),
        label = "styleMenuReveal",
    )

    BackHandler(enabled = open) { onOpenChange(false) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        if (reveal > 0.001f) {
            // Tapping the map around the menu closes it.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        onOpenChange(false)
                    },
            )
            val cardShape = RoundedCornerShape(28.dp)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = MARGIN, end = MARGIN, start = MARGIN)
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
                    .height(minOf(maxHeight * 0.72f, 560.dp))
                    // The reveal: a circle centred on the button that grows to cover the card.
                    .graphicsLayer {
                        clip = true
                        shape = GenericShape { size, _ ->
                            val origin = Offset(size.width - BUTTON.toPx() / 2, BUTTON.toPx() / 2)
                            val radius = hypot(size.width, size.height) * reveal
                            addOval(Rect(origin, radius))
                        }
                        alpha = (reveal * 1.6f).coerceAtMost(1f)
                    }
                    .clip(cardShape)
                    .hazeBlur(HazeInput.Sources(hazeState), style = glass)
                    .border(1.dp, edge, cardShape)
                    // Swallow taps so they don't fall through to the close-on-tap layer.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                EditorContent(editor, Modifier.fillMaxSize().padding(top = 4.dp))
            }
        }

        // The button sits on top so it doubles as the close control while the menu is open.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(MARGIN)
                .size(BUTTON)
                .clip(CircleShape)
                .hazeBlur(HazeInput.Sources(hazeState), style = glass)
                .border(1.dp, edge, CircleShape)
                .clickable { onOpenChange(!open) },
            contentAlignment = Alignment.Center,
        ) {
            MenuGlyph(ink, closeness = reveal)
        }
        // A dot on the button while an update is waiting (see the Updates tab).
        val update by AppUpdates.state.collectAsState()
        if (update.available && !open) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = MARGIN + 4.dp, end = MARGIN + 4.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE5484D)),
            )
        }
    }
}

private val BUTTON = 48.dp
private val MARGIN = 14.dp

/**
 * Three slider lines with knobs (style controls) that fold into an X as the menu opens.
 * [closeness] runs 0 (sliders) to 1 (X).
 */
@Composable
private fun MenuGlyph(color: Color, closeness: Float) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        val w = size.width
        val h = size.height
        val c = closeness.coerceIn(0f, 1f)
        val rows = listOf(0.2f, 0.5f, 0.8f)
        val knobs = listOf(0.68f, 0.32f, 0.56f)
        rows.forEachIndexed { i, row ->
            val y = h * row
            when (i) {
                // Top and bottom lines swing onto the X's two diagonals.
                0 -> drawLine(color, Offset(w * 0.1f, lerp(y, h * 0.18f, c)), Offset(w * 0.9f, lerp(y, h * 0.82f, c)), stroke, StrokeCap.Round)
                2 -> drawLine(color, Offset(w * 0.1f, lerp(y, h * 0.82f, c)), Offset(w * 0.9f, lerp(y, h * 0.18f, c)), stroke, StrokeCap.Round)
                // The middle line fades out.
                else -> drawLine(color.copy(alpha = color.alpha * (1 - c)), Offset(w * 0.1f, y), Offset(w * 0.9f, y), stroke, StrokeCap.Round)
            }
            // Knobs shrink away as it becomes an X.
            drawCircle(color.copy(alpha = color.alpha * (1 - c)), radius = stroke * 1.9f * (1 - c), center = Offset(w * knobs[i], y))
        }
    }
}
