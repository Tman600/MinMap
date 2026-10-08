package com.example.mapstyleeditor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mapstyleeditor.nav.NavInstruction
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * Drive-mode chrome over the map: Google's next turn in a frosted card at the top, and an End
 * button at the bottom. [instruction] is null until Google Maps' guidance shows up.
 */
@Composable
fun DriveOverlay(
    hazeState: HazeState,
    darkMap: Boolean,
    instruction: NavInstruction?,
    /** Whether the camera is on the low chase view rather than the standard one. */
    closeUp: Boolean,
    /** False after you've moved the map by hand; shows the re-center button. */
    following: Boolean,
    onToggleView: () -> Unit,
    onRecenter: () -> Unit,
    onEnd: () -> Unit,
) {
    val ink = if (darkMap) Color.White else Color(0xFF1C1C1E)
    val glass = remember(darkMap) { glassStyle(darkMap) }
    val glassBorder = if (darkMap) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.6f)

    Box(Modifier.fillMaxSize().padding(12.dp)) {
        val cardShape = RoundedCornerShape(26.dp)
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .clip(cardShape)
                .hazeBlur(HazeInput.Sources(hazeState), style = glass)
                .border(1.dp, glassBorder, cardShape)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val arrow = instruction?.arrow
            if (arrow != null) {
                Image(
                    arrow.asImageBitmap(),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(ink),
                    modifier = Modifier.size(52.dp),
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                if (instruction == null) {
                    Text("Starting Google Maps…", color = ink, fontSize = 18.sp)
                    Text(
                        "Directions appear here once its navigation is running.",
                        color = ink.copy(alpha = 0.65f),
                        fontSize = 13.sp,
                    )
                } else {
                    instruction.distanceText?.let {
                        Text(it, color = ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        instruction.instruction,
                        color = ink,
                        fontSize = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    instruction.arrival?.let {
                        Text(it, color = ink.copy(alpha = 0.65f), fontSize = 13.sp)
                    }
                }
            }
        }

        val pill = RoundedCornerShape(50)
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
                .clip(pill)
                .hazeBlur(HazeInput.Sources(hazeState), style = glass)
                .border(1.dp, glassBorder, pill)
                .clickable(onClick = onEnd)
                .padding(horizontal = 32.dp, vertical = 12.dp),
        ) {
            Text("End", color = Color(0xFFE5484D), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        // Camera view: standard follow, or the low "close-up" chase view behind the puck.
        GlassPill(
            label = if (closeUp) "Standard" else "Close-up",
            ink = ink, edge = glassBorder, hazeState = hazeState, glass = glass,
            onClick = onToggleView,
            modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 28.dp),
        )
        // Only needed after the map has been moved by hand (it also re-follows on its own shortly).
        if (!following) {
            GlassPill(
                label = "Re-center",
                ink = ink, edge = glassBorder, hazeState = hazeState, glass = glass,
                onClick = onRecenter,
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 28.dp),
            )
        }
    }
}

@Composable
private fun GlassPill(
    label: String,
    ink: Color,
    edge: Color,
    hazeState: HazeState,
    glass: HazeBlurStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pill = RoundedCornerShape(50)
    Box(
        modifier
            .clip(pill)
            .hazeBlur(HazeInput.Sources(hazeState), style = glass)
            .border(1.dp, edge, pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(label, color = ink, fontSize = 15.sp)
    }
}
