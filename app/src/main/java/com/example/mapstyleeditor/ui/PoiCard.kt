package com.example.mapstyleeditor.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mapstyleeditor.places.PoiDetails
import com.example.mapstyleeditor.places.TappedPoi
import com.example.mapstyleeditor.places.loadPoiDetails
import com.example.mapstyleeditor.robotaxi.RobotaxiProvider
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import kotlin.math.roundToInt

/**
 * Frosted card for a tapped POI: photo (when Wikipedia has one), name, category, address, rating
 * and today's hours (when Mapbox has them), and actions: Drive, open in Google Maps, call, website.
 */
@Composable
fun PoiCard(
    poi: TappedPoi,
    hazeState: HazeState,
    darkMap: Boolean,
    accessToken: String,
    /** Robotaxi operators whose service area includes this place. */
    robotaxi: List<RobotaxiProvider>,
    onDrive: (Place) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var details by remember(poi) { mutableStateOf<PoiDetails?>(null) }
    LaunchedEffect(poi) { details = loadPoiDetails(accessToken, poi) }

    val ink = if (darkMap) Color.White else Color(0xFF1C1C1E)
    val soft = ink.copy(alpha = 0.65f)
    val glass = remember(darkMap) { glassStyle(darkMap) }
    val edge = if (darkMap) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.6f)
    val shape = RoundedCornerShape(26.dp)

    Column(
        modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp)
            .clip(shape)
            .hazeBlur(HazeInput.Sources(hazeState), style = glass)
            .border(1.dp, edge, shape)
            .animateContentSize(),
    ) {
        details?.photo?.let { photo ->
            Image(
                photo.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(150.dp),
            )
        }
        Column(Modifier.padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(poi.name, color = ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val d = details
                    val subtitle = listOfNotNull(d?.category ?: d?.about ?: poi.group?.prettyGroup(), d?.address).joinToString(" · ")
                    if (subtitle.isNotEmpty()) Text(subtitle, color = soft, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    d?.rating?.let { RatingRow(it, ink, soft) }
                    d?.hoursToday?.let { Text(it, color = soft, fontSize = 13.sp) }
                    if (d == null) Text("Looking up details…", color = soft, fontSize = 13.sp)
                }
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = soft, fontSize = 15.sp) }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val place = Place(poi.name, details?.address.orEmpty(), poi.point.longitude(), poi.point.latitude())
                ActionPill("Drive", ink, edge, primary = true) { onDrive(place) }
                robotaxi.forEach { RobotaxiPill(it, ink, edge) }
                ActionPill("Google Maps", ink, edge) {
                    val q = Uri.encode("${poi.name}${details?.address?.let { ", $it" }.orEmpty()}")
                    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${poi.point.latitude()},${poi.point.longitude()}?q=$q"))
                        .setPackage("com.google.android.apps.maps")
                    try {
                        context.startActivity(geo)
                    } catch (_: ActivityNotFoundException) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$q")))
                    }
                }
                details?.phone?.let { phone ->
                    ActionPill("Call", ink, edge) { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }
                }
                details?.website?.let { site ->
                    ActionPill("Website", ink, edge) {
                        val url = if (site.startsWith("http")) site else "https://$site"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingRow(rating: Double, ink: Color, soft: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(String.format("%.1f", rating), color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(4.dp))
        // Ratings come back on a 0–5 scale; show whole stars, rounded.
        val stars = rating.roundToInt().coerceIn(0, 5)
        Text("★".repeat(stars) + "☆".repeat(5 - stars), color = Color(0xFFF5A623), fontSize = 13.sp)
        Text("  Mapbox", color = soft, fontSize = 11.sp)
    }
}

@Composable
private fun ActionPill(label: String, ink: Color, edge: Color, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .then(if (primary) Modifier.border(1.5.dp, ink.copy(alpha = 0.8f), shape) else Modifier.border(1.dp, edge, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(label, color = ink, fontSize = 14.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Standard's POI "group" values are snake_case ("food_and_drink"); make them readable. */
private fun String.prettyGroup() = replace('_', ' ').replaceFirstChar { it.uppercase() }
