package com.example.mapstyleeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mapstyleeditor.robotaxi.RobotaxiProvider
import com.example.mapstyleeditor.robotaxi.openRobotaxiApp

/**
 * Small "● Waymo ↗" pill that opens that operator's rider app. Shown next to Drive when the place
 * is inside the operator's service area. [modifier] supplies the background (glass or plain).
 */
@Composable
fun RobotaxiPill(provider: RobotaxiProvider, ink: Color, edge: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .border(1.dp, edge, shape)
            .clickable { openRobotaxiApp(context, provider) }
            .padding(start = 12.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(provider.color))
        Spacer(Modifier.width(7.dp))
        Text(provider.label, color = ink, fontSize = 14.sp)
        Text(" ↗", color = ink.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(start = 1.dp))
    }
}
