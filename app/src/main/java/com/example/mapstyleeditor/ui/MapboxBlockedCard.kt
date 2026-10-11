package com.example.mapstyleeditor.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mapstyleeditor.account.MapboxUsage
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * Mapbox's free monthly allowance on a standard account, for what MinMap uses (from
 * mapbox.com/pricing, October 2026; searches are at Mapbox's introductory preview figure).
 */
private val FREE_EACH_MONTH = listOf(
    "The map" to "25,000 phones",
    "Searches" to "500",
    "Nearby lists" to "50,000",
    "Routes" to "100,000",
    "Street lookups" to "100,000",
)

/**
 * Shown when Mapbox refuses the signed-in account's token (see MapboxUsage.blocked). A "demo
 * access" Mapbox account loses every service once any of its small monthly caps is reached, and
 * only a card on the account lifts that: it becomes a standard account, with the free monthly
 * allowances above and charges only beyond them.
 */
@Composable
fun MapboxBlockedCard(hazeState: HazeState, darkMap: Boolean, onUseAnotherAccount: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ink = if (darkMap) Color.White else Color(0xFF1C1C1E)
    val muted = ink.copy(alpha = 0.72f)
    val glass = remember(darkMap) { glassStyle(darkMap) }
    val edge = if (darkMap) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.6f)
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .widthIn(max = 520.dp)
            .clip(shape)
            .hazeBlur(HazeInput.Sources(hazeState), style = glass)
            .border(1.dp, edge, shape)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Mapbox has paused this account", color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "It's on Mapbox's free demo, and the demo's allowance is used up, so Mapbox has stopped " +
                "sending maps and search results.",
            color = muted,
            fontSize = 14.sp,
        )
        Text(
            "To get past the demo you need to add a card on Mapbox. That makes it a standard account, " +
                "and the map comes straight back. The card is only charged for use beyond the free " +
                "tier, which includes each month:",
            color = ink,
            fontSize = 14.sp,
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FREE_EACH_MONTH.forEach { (what, amount) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(what, color = muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(amount, color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text(
            (MapboxUsage.summary()?.let { "MinMap's use on this account this month: $it. " } ?: "") +
                "The map counts each phone once a month, however much it's used.",
            color = muted,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 2.dp)) {
            Text(
                "Add a card on Mapbox",
                color = Color(0xFF2F7BFF),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.mapbox.com/")))
                },
            )
            Text(
                "Check again",
                color = muted,
                fontSize = 15.sp,
                modifier = Modifier.clickable { MapboxUsage.confirm(force = true) },
            )
            Text(
                "Switch account",
                color = muted,
                fontSize = 15.sp,
                modifier = Modifier.clickable(onClick = onUseAnotherAccount),
            )
        }
    }
}
